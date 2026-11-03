package main

import (
 "bufio"
 "encoding/hex"
 "errors"
 "flag"
 "fmt"
 "net"
 "net/netip"
 "os"
 "path/filepath"
 "strconv"
 "strings"
 "syscall"

 "github.com/WWWorm92/NetherVPN/internal/app"
 "github.com/WWWorm92/NetherVPN/internal/handshake"
 "github.com/WWWorm92/NetherVPN/internal/peers"
 "github.com/WWWorm92/NetherVPN/internal/profile"
)
func fail(err error){fmt.Fprintln(os.Stderr,"nether-admin:",err);os.Exit(1)}

func main(){
 configDefault:="/etc/nethervpn/server.conf"
 dirDefault:="/etc/nethervpn/peers"
 outputDefault:="/etc/nethervpn/clients"
 if len(os.Args)==1 {
  if err:=runMenu(bufio.NewReader(os.Stdin),os.Stdout,configDefault,dirDefault,outputDefault);err!=nil{fail(err)}
  return
 }
 command:=os.Args[1]
 flags:=flag.NewFlagSet(command,flag.ExitOnError)
 config:=flags.String("server-config",configDefault,"server profile")
 dir:=flags.String("peers-dir",dirDefault,"client public-key registry")
 output:=flags.String("output-dir",outputDefault,"client secret profiles")
 name:=flags.String("name","","client name")
 endpoint:=flags.String("endpoint","","server public IPv4:UDP port")
 if err:=flags.Parse(os.Args[2:]);err!=nil{fail(err)}
 switch command{
 case "create":
  if *name==""||*endpoint==""{
   reader:=bufio.NewReader(os.Stdin)
   value,err:=askClient(reader,os.Stdout,*config,*name,*endpoint)
   if err!=nil{fail(err)}
   if err=create(*config,*dir,*output,value.Name,value.Endpoint);err!=nil{fail(err)}
   askRestart(reader,os.Stdout)
  } else if err:=create(*config,*dir,*output,*name,*endpoint);err!=nil{fail(err)}
 case "list":
  if err:=listClients(os.Stdout,*dir);err!=nil{fail(err)}
 case "revoke":
  if *name==""{fail(errors.New("specify -name"))}
  if err:=revoke(*dir,*output,*name);err!=nil{fail(err)}
 default:fail(errors.New("commands: create, list, revoke; no arguments opens interactive menu"))
 }
}

func validateEndpoint(s string) error{
 host,port,err:=net.SplitHostPort(s)
 if err!=nil{return fmt.Errorf("endpoint: use numeric IPv4:port: %w",err)}
 ip:=net.ParseIP(host)
 if ip==nil||ip.To4()==nil||ip.IsUnspecified()||ip.IsLoopback(){return errors.New("endpoint must be a public server IPv4 address")}
 n,err:=strconv.Atoi(port)
 if err!=nil||n<1||n>65535{return errors.New("invalid endpoint port")}
 return nil
}

func create(serverConfig,dir,outputDir,name,endpoint string)error{
 if !peers.ValidName(name){return errors.New("-name requires 1..48 ASCII letters, digits, _ or -")}
 if err:=validateEndpoint(endpoint);err!=nil{return err}
 cfg,err:=profile.Load(serverConfig)
 if err!=nil{return err}
 prefix,err:=netip.ParsePrefix(cfg.Address)
 if err!=nil||!prefix.Addr().Is4(){return errors.New("server Address must be IPv4 CIDR")}
 prefix=prefix.Masked()
 if prefix.Bits()>29 || prefix.Bits()<24 {
  return errors.New("server Address must be /24../29; migrate 10.77.0.1/30 to 10.77.0.1/24")
 }
 local,err:=netip.ParseAddr(strings.Split(cfg.Address,"/")[0]);if err!=nil{return err}
 legacy,err:=netip.ParseAddr(cfg.AllowedIP);if err!=nil{return err}
 if !prefix.Contains(legacy){return errors.New("legacy peer IP outside server subnet")}
 identity,err:=app.ParseIdentity(cfg.PrivateKey);if err!=nil{return err}
 for _,path:=range []string{dir,outputDir}{
  if err=os.MkdirAll(path,0700);err!=nil{return err}
  if err=os.Chmod(path,0700);err!=nil{return err}
 }
 // Serialize generation and allocation across parallel administrators.
 lock,err:=os.Open(dir);if err!=nil{return err}
 defer lock.Close()
 if err=syscall.Flock(int(lock.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(lock.Fd()),syscall.LOCK_UN)
 registry,err:=peers.Load(dir);if err!=nil{return err}
 occupied:=map[netip.Addr]bool{local:true,legacy:true}
 for _,p:=range registry{occupied[p.IP]=true}
 var selected netip.Addr
 for addr:=prefix.Addr().Next();prefix.Contains(addr);addr=addr.Next(){
  if addr.Next().IsValid()&&!prefix.Contains(addr.Next()){break}
  if !occupied[addr]{selected=addr;break}
 }
 if !selected.IsValid(){return errors.New("no available VPN addresses")}
 fresh,err:=handshake.GenerateIdentity();if err!=nil{return err}
 var clientPub [32]byte;copy(clientPub[:],fresh.Public)
 record:=peers.Peer{Name:name,IP:selected,PublicKey:clientPub}
 // Universal identity-only profile: OS, traffic mode, DNS and autostart belong
 // exclusively to clients' local settings, NEVER the server provisioning data.
 clientText:=fmt.Sprintf("[Interface]\nName = nvpn0\nPrivateKey = %s\nAddress = %s/32\nMTU = 1280\n\n[Peer]\nPublicKey = %s\nEndpoint = %s\nAllowedIPs = %s\n",
  hex.EncodeToString(fresh.Private),selected,hex.EncodeToString(identity.Public),endpoint,local)
 secretPath:=filepath.Join(outputDir,name+".conf")
 publicPath:=filepath.Join(dir,name+".conf")
 secret,err:=os.OpenFile(secretPath,os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600)
 if err!=nil{return err}
 cleanup:=true
 defer func(){if cleanup{os.Remove(secretPath)}}()
 if _,err=secret.WriteString(clientText);err!=nil{secret.Close();return err}
 if err=secret.Sync();err!=nil{secret.Close();return err}
 if err=secret.Close();err!=nil{return err}
 authorized,err:=os.OpenFile(publicPath,os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600)
 if err!=nil{return err}
 if _,err=authorized.WriteString(peers.Format(record));err!=nil{authorized.Close();os.Remove(publicPath);return err}
 if err=authorized.Sync();err!=nil{authorized.Close();os.Remove(publicPath);return err}
 if err=authorized.Close();err!=nil{os.Remove(publicPath);return err}
 cleanup=false
 fmt.Printf("Клиент %s создан (VPN IP %s)\nФайл: %s (0600)\n",name,selected,secretPath)
 return nil
}

func listClients(out interface{Write([]byte)(int,error)},dir string)error{
 entries,err:=peers.Load(dir);if err!=nil{return err}
 if len(entries)==0{fmt.Fprintln(out,"Клиентов пока нет");return nil}
 for _,p:=range entries{fmt.Fprintf(out,"%-20s %-16s %x\n",p.Name,p.IP,p.PublicKey)}
 return nil
}
func revoke(dir,outputDir,name string)error{
 if !peers.ValidName(name){return errors.New("invalid client name")}
 if err:=os.Remove(filepath.Join(dir,name+".conf"));err!=nil{return err}
 _=os.Remove(filepath.Join(outputDir,name+".conf"))
 return nil
}

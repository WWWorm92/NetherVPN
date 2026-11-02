// nether-admin creates individual client identities and root-only .conf files.
// The server stores only public keys in its authorized peer registry.
package main

import (
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

func fail(err error) {fmt.Fprintln(os.Stderr,"nether-admin:",err);os.Exit(1)}

func main(){
 if len(os.Args)<2 {fail(errors.New("usage: nether-admin create|list|revoke [options]"))}
 action:=os.Args[1]
 flags:=flag.NewFlagSet(action,flag.ExitOnError)
 config:=flags.String("server-config","/etc/nethervpn/server.conf","server config file")
 dir:=flags.String("peers-dir","/etc/nethervpn/peers","server public key registry")
 outputDir:=flags.String("output-dir","/etc/nethervpn/clients","root-only exported client configurations")
 name:=flags.String("name","","client identifier (letters, digits, _ or -)")
 endpoint:=flags.String("endpoint","","public server IPv4:UDP port")
 dns:=flags.String("dns","1.1.1.1","DNS resolver accessed inside the tunnel")
 if err:=flags.Parse(os.Args[2:]);err!=nil{fail(err)}
 switch action{
 case "create":
  if err:=create(*config,*dir,*outputDir,*name,*endpoint,*dns);err!=nil{fail(err)}
 case "list":
  existing,err:=peers.Load(*dir);if err!=nil{fail(err)}
  for _,p:=range existing{fmt.Printf("%-20s %-16s %x\n",p.Name,p.IP,p.PublicKey)}
 case "revoke":
  if !peers.ValidName(*name){fail(errors.New("invalid -name"))}
  file:=filepath.Join(*dir,*name+".conf")
  if err:=os.Remove(file);err!=nil{fail(err)}
  // If present, remove the locally exported secret too. A transferred copy
  // may exist; actual revocation takes effect on server service restart.
  _=os.Remove(filepath.Join(*outputDir,*name+".conf"))
  fmt.Printf("Revoked %s; restart nethervpn-server to apply.\n",*name)
 default:fail(fmt.Errorf("unknown action: %s",action))
 }
}

func validateEndpoint(s string) error{
 host,port,err:=net.SplitHostPort(s)
 if err!=nil{return fmt.Errorf("endpoint: use public IPv4:port: %w",err)}
 ip:=net.ParseIP(host)
 if ip==nil||ip.To4()==nil||ip.IsUnspecified()||ip.IsLoopback(){return errors.New("endpoint must be a public server IPv4 address")}
 n,err:=strconv.Atoi(port)
 if err!=nil||n<1||n>65535{return errors.New("invalid endpoint port")}
 return nil
}

func create(serverConfig,dir,outputDir,name,endpoint,dns string) error {
 if !peers.ValidName(name){return errors.New("-name requires 1..48 ASCII letters, digits, _ or -")}
 if err:=validateEndpoint(endpoint);err!=nil{return err}
 if ip:=net.ParseIP(dns);ip==nil||ip.To4()==nil||ip.IsUnspecified(){return errors.New("-dns must be an IPv4 address")}
 cfg,err:=profile.Load(serverConfig)
 if err!=nil{return err}
 prefix,err:=netip.ParsePrefix(cfg.Address)
 if err!=nil||!prefix.Addr().Is4(){return errors.New("server Address must be IPv4 CIDR")}
 prefix=prefix.Masked()
 // /24 or larger subnet is a prerequisite for assigning multiple clients.
 if prefix.Bits()>29 || prefix.Bits()<24 {
  return errors.New("server Interface.Address must use a /24../29 subnet; migrate 10.77.0.1/30 to 10.77.0.1/24")
 }
 local,err:=netip.ParseAddr(strings.Split(cfg.Address,"/")[0])
 if err!=nil{return err}
 legacy,err:=netip.ParseAddr(cfg.AllowedIP)
 if err!=nil{return err}
 if !prefix.Contains(legacy) {return errors.New("existing legacy Peer.AllowedIPs outside subnet")}
 identity,err:=app.ParseIdentity(cfg.PrivateKey)
 if err!=nil{return err}
 if err=os.MkdirAll(dir,0700);err!=nil{return err}
 if err=os.Chmod(dir,0700);err!=nil{return err}
 if err=os.MkdirAll(outputDir,0700);err!=nil{return err}
 if err=os.Chmod(outputDir,0700);err!=nil{return err}

 // Serialize concurrent create actions, so they cannot assign the same address.
 lock,err:=os.Open(dir)
 if err!=nil{return err}
 defer lock.Close()
 if err=syscall.Flock(int(lock.Fd()),syscall.LOCK_EX);err!=nil{return err}
 defer syscall.Flock(int(lock.Fd()),syscall.LOCK_UN)

 registered,err:=peers.Load(dir)
 if err!=nil{return err}
 occupied:=map[netip.Addr]bool{local:true,legacy:true}
 for _,p:=range registered {occupied[p.IP]=true}
 var selected netip.Addr
 for candidate:=prefix.Addr().Next();prefix.Contains(candidate);candidate=candidate.Next(){
  // Avoid the broadcast address and network identifier.
  if candidate.Next().IsValid()&&!prefix.Contains(candidate.Next()){break}
  if !occupied[candidate] {selected=candidate;break}
 }
 if !selected.IsValid(){return errors.New("no available client IPv4 addresses")}
 fresh,err:=handshake.GenerateIdentity()
 if err!=nil{return err}
 var serverPub [32]byte
 copy(serverPub[:],identity.Public)
 var clientPub [32]byte
 copy(clientPub[:],fresh.Public)
 record:=peers.Peer{Name:name,IP:selected,PublicKey:clientPub}
 clientText:=fmt.Sprintf("[Interface]\nName = nvpn0\nPrivateKey = %s\nAddress = %s/32\nMTU = 1280\nFullTunnel = true\nDNS = %s\n\n[Peer]\nPublicKey = %s\nEndpoint = %s\nAllowedIPs = %s\n",
  hex.EncodeToString(fresh.Private),selected,dns,hex.EncodeToString(serverPub[:]),endpoint,local.String())
 // Secure directory permissions, O_EXCL prevents accidental replacement.
 registryPath:=filepath.Join(dir,name+".conf")
 secretPath:=filepath.Join(outputDir,name+".conf")
 output,err:=os.OpenFile(secretPath,os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600)
 if err!=nil{return err}
 defer output.Close()
 cleanup:=true
 defer func(){if cleanup{_ = os.Remove(secretPath)}}()
 if _,err=output.WriteString(clientText);err!=nil{return err}
 if err=output.Sync();err!=nil{return err}
 f,err:=os.OpenFile(registryPath,os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600)
 if err!=nil{return err}
 if _,err=f.WriteString(peers.Format(record));err!=nil {f.Close();os.Remove(registryPath);return err}
 if err=f.Sync();err!=nil {f.Close();os.Remove(registryPath);return err}
 if err=f.Close();err!=nil {os.Remove(registryPath);return err}
 cleanup=false
 fmt.Printf("Created %s (%s)\nClient profile: %s (mode 0600)\nAuthorized key: %s\nRestart nethervpn-server to activate this client.\n",
  name,selected,secretPath,registryPath)
 return nil
}

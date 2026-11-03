package main

import (
 "fmt"
 "os"
 "path/filepath"
 "strings"
 "testing"

 "github.com/WWWorm92/NetherVPN/internal/handshake"
 "github.com/WWWorm92/NetherVPN/internal/peers"
)

func TestCreateDistinctClientsAndProtectSecrets(t *testing.T) {
 server,err:=handshake.GenerateIdentity()
 if err!=nil{t.Fatal(err)}
 legacy,err:=handshake.GenerateIdentity()
 if err!=nil{t.Fatal(err)}
 dir:=t.TempDir()
 serverPath:=filepath.Join(dir,"server.conf")
 config:=fmt.Sprintf("[Interface]\nName = nvpn0\nPrivateKey = %x\nAddress = 10.77.0.1/24\nListenPort = 51830\nMTU = 1280\n\n[Peer]\nPublicKey = %x\nAllowedIPs = 10.77.0.2\n",server.Private,legacy.Public)
 if err:=os.WriteFile(serverPath,[]byte(config),0600);err!=nil{t.Fatal(err)}
 peersDir:=filepath.Join(dir,"peers")
 exports:=filepath.Join(dir,"clients")
 for _,name:=range []string{"android","tablet"}{
  if err:=create(serverPath,peersDir,exports,name ,"45.151.69.184:51830");err!=nil{t.Fatal(err)}
 }
 records,err:=peers.Load(peersDir)
 if err!=nil{t.Fatal(err)}
 if len(records)!=2||records[0].IP==records[1].IP{t.Fatalf("duplicate IP: %v",records)}
 if records[0].IP.String()!="10.77.0.3"||records[1].IP.String()!="10.77.0.4"{t.Fatalf("unexpected assignments: %+v",records)}
 for _,name:=range []string{"android","tablet"}{
  file:=filepath.Join(exports,name+".conf")
  info,err:=os.Stat(file)
  if err!=nil{t.Fatal(err)}
  if info.Mode().Perm()!=0600{t.Errorf("unsafe mode %o",info.Mode().Perm())}
  b,err:=os.ReadFile(file)
  if err!=nil{t.Fatal(err)}
  content:=string(b)
  if strings.Contains(content,"FullTunnel")||strings.Contains(content,"DNS =")||strings.Contains(content,"android"){t.Fatal("profile contains app-specific settings")}
  if !strings.Contains(content,fmt.Sprintf("PublicKey = %x",server.Public)){t.Fatal("wrong pinned server key")}
  if strings.Contains(content,fmt.Sprintf("%x",server.Private)){t.Fatal("server private key leaked")}
 }
 if err:=create(serverPath,peersDir,exports,"android" ,"45.151.69.184:51830");err==nil{
  t.Fatal("duplicate client was overwritten")
 }
}

func TestRejectUnsafeInputs(t *testing.T) {
 for _,endpoint:=range []string{"localhost:51830","bad-host:51830","127.0.0.1:51830","1.2.3.4:0","1.2.3.4:70000"}{
  if err:=validateEndpoint(endpoint);err==nil{t.Fatalf("accepted endpoint %s",endpoint)}
 }
}

func TestUniversalProfile(t *testing.T) {
 server,err:=handshake.GenerateIdentity()
 if err!=nil{t.Fatal(err)}
 legacy,err:=handshake.GenerateIdentity()
 if err!=nil{t.Fatal(err)}
 dir:=t.TempDir()
 path:=filepath.Join(dir,"server.conf")
 raw:=fmt.Sprintf("[Interface]\nName = nvpn0\nPrivateKey = %x\nAddress = 10.77.0.1/24\nListenPort = 51830\n\n[Peer]\nPublicKey = %x\nAllowedIPs = 10.77.0.2\n",server.Private,legacy.Public)
 if err:=os.WriteFile(path,[]byte(raw),0600);err!=nil{t.Fatal(err)}
 exports:=filepath.Join(dir,"clients")
 if err:=create(path,filepath.Join(dir,"peers"),exports,"laptop","77.221.132.76:51830");err!=nil{t.Fatal(err)}
 data,err:=os.ReadFile(filepath.Join(exports,"laptop.conf"))
 if err!=nil{t.Fatal(err)}
 conf:=string(data)
 for _,expected:=range []string{"Address = 10.77.0.3/32","Endpoint = 77.221.132.76:51830","AllowedIPs = 10.77.0.1"} {
  if !strings.Contains(conf,expected){t.Fatalf("missing %q",expected)}
 }
 for _,forbidden:=range []string{"FullTunnel","DNS","android","linux","Mode"}{
  if strings.Contains(conf,forbidden){t.Fatalf("universal profile leaked %s",forbidden)}
 }
}

// Package profile parses NetherVPN's WireGuard-style INI configuration.
// The format is deliberately not WireGuard-compatible at the protocol level.
package profile

import (
 "errors"
 "fmt"
 "net/netip"
 "os"
 "strconv"
 "strings"
)
type Profile struct {
 Name, PrivateKey, Address, ListenPort, PublicKey, Endpoint, AllowedIP, Interface string
 MTU int
}
func Parse(data string)(Profile,error){
 p:=Profile{MTU:1280,Interface:"nvpn0"}
 section:=""
 for n,line:=range strings.Split(data,"\n"){
  line=strings.TrimSpace(line)
  if line==""||strings.HasPrefix(line,"#")||strings.HasPrefix(line,";"){continue}
  if strings.HasPrefix(line,"[")&&strings.HasSuffix(line,"]"){section=strings.ToLower(strings.TrimSpace(line[1:len(line)-1]));continue}
  parts:=strings.SplitN(line,"=",2)
  if len(parts)!=2{return p,fmt.Errorf("profile: line %d: expected key=value",n+1)}
  key,val:=strings.ToLower(strings.TrimSpace(parts[0])),strings.TrimSpace(parts[1])
  switch section+"."+key{
  case "interface.privatekey":p.PrivateKey=val
  case "interface.address":p.Address=val
  case "interface.name":p.Interface=val
  case "interface.mtu":i,e:=strconv.Atoi(val);if e!=nil{return p,fmt.Errorf("profile: invalid MTU")};p.MTU=i
  case "interface.listenport":p.ListenPort=val
  case "peer.publickey":p.PublicKey=val
  case "peer.endpoint":p.Endpoint=val
  case "peer.allowedips":p.AllowedIP=val
  default:return p,fmt.Errorf("profile: unsupported key %s.%s",section,key)
  }
 }
 if p.PrivateKey==""||p.PublicKey==""||p.Address==""||p.AllowedIP==""{return p,errors.New("profile: missing required key")}
 if _,e:=netip.ParsePrefix(p.Address);e!=nil{return p,fmt.Errorf("profile: invalid Address: %w",e)}
 if _,e:=netip.ParseAddr(p.AllowedIP);e!=nil{return p,fmt.Errorf("profile: AllowedIPs must be one peer IP: %w",e)}
 if p.MTU<40||p.MTU>9000{return p,errors.New("profile: invalid MTU")}
 return p,nil
}
func Load(path string)(Profile,error){b,e:=os.ReadFile(path);if e!=nil{return Profile{},e};return Parse(string(b))}

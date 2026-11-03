// Package clientsettings stores Linux-only routing policy independently of
// the portable NetherVPN identity file. Never place private keys here.
package clientsettings

import (
 "errors"
 "fmt"
 "net/netip"
 "os"
 "strings"
)

type Settings struct {
 FullTunnel bool
 DNS string
}
func Default()Settings{return Settings{DNS:"1.1.1.1"}}
func Parse(text string)(Settings,error){
 s:=Default()
 if len(text)>4096{return s,errors.New("settings: file too large")}
 fields:=make(map[string]bool)
 section:=""
 hasMode:=false
 for _,raw:=range strings.Split(text,"\n"){
  line:=strings.TrimSpace(raw)
  if line==""||strings.HasPrefix(line,"#")||strings.HasPrefix(line,";"){continue}
  if strings.HasPrefix(line,"[")&&strings.HasSuffix(line,"]"){
   section=strings.ToLower(strings.TrimSpace(line[1:len(line)-1]))
   if section!="client"{return s,errors.New("settings: only [Client] section is supported")}
   continue
  }
  key,value,found:=strings.Cut(line,"=")
  if !found||section!="client"{return s,fmt.Errorf("settings: invalid line %q",line)}
  key=strings.ToLower(strings.TrimSpace(key));value=strings.TrimSpace(value)
  if fields[key]{return s,fmt.Errorf("settings: repeated %s",key)}
  fields[key]=true
  switch key{
  case "mode":
   switch strings.ToLower(value){
   case "full":s.FullTunnel=true
   case "split":s.FullTunnel=false
   default:return s,errors.New("settings: Mode must be full or split")
   }
   hasMode=true
  case "dns":s.DNS=value
  // This local value is consumed by the desktop systemd UI, not the Go engine.
  case "autoconnect":
   if value!="true"&&value!="false"{return s,errors.New("settings: AutoConnect must be true or false")}
  default:return s,fmt.Errorf("settings: unsupported %s",key)
  }
 }
 if !hasMode{return s,errors.New("settings: missing Mode")}
 dns,err:=netip.ParseAddr(s.DNS)
 if err!=nil||!dns.Is4()||dns.IsUnspecified(){return s,errors.New("settings: DNS must be numeric IPv4")}
 return s,nil
}

func LoadIfExists(path string)(Settings,bool,error){
 if path==""{return Default(),false,nil}
 data,err:=os.ReadFile(path)
 if errors.Is(err,os.ErrNotExist){return Default(),false,nil}
 if err!=nil{return Default(),false,err}
 s,err:=Parse(string(data))
 return s,true,err
}

// Package peers manages root-owned server-side public client identities.
// No private keys belong in this registry.
package peers

import (
 "bufio"
 "encoding/hex"
 "errors"
 "fmt"
 "io"
 "net/netip"
 "os"
 "path/filepath"
 "regexp"
 "strings"
)

var validName = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,48}$`)

type Peer struct {
 Name string
 PublicKey [32]byte
 IP netip.Addr
}

func ValidName(name string) bool { return validName.MatchString(name) }

func Parse(name string, content string) (Peer,error) {
 var p Peer
 if !ValidName(name) { return p, fmt.Errorf("peers: invalid name %q",name) }
 if len(content)>4096 { return p, errors.New("peers: config too large") }
 p.Name=name
 seen:=make(map[string]bool)
 scanner:=bufio.NewScanner(strings.NewReader(content))
 section:=""
 for scanner.Scan() {
  line:=strings.TrimSpace(scanner.Text())
  if line==""||strings.HasPrefix(line,"#")||strings.HasPrefix(line,";"){continue}
  if strings.HasPrefix(line,"[")&&strings.HasSuffix(line,"]"){
   section=strings.ToLower(strings.TrimSpace(line[1:len(line)-1]))
   if section!="peer"{return Peer{},errors.New("peers: expected [Peer]")}
   continue
  }
  pieces:=strings.SplitN(line,"=",2)
  if section!="peer"||len(pieces)!=2 {return Peer{},errors.New("peers: invalid peer line")}
  key,value:=strings.ToLower(strings.TrimSpace(pieces[0])),strings.TrimSpace(pieces[1])
  if seen[key] {return Peer{},fmt.Errorf("peers: repeated %s",key)}
  seen[key]=true
  switch key {
  case "publickey":
   raw,err:=hex.DecodeString(value)
   if err!=nil||len(raw)!=32{return Peer{},errors.New("peers: invalid public key")}
   copy(p.PublicKey[:],raw)
  case "allowedips":
   if strings.Contains(value,"/"){return Peer{},errors.New("peers: allowedips must be a single address")}
   var err error
   p.IP,err=netip.ParseAddr(value)
   if err!=nil||!p.IP.Is4()||p.IP.IsUnspecified(){return Peer{},errors.New("peers: IPv4 peer address required")}
  default:return Peer{},fmt.Errorf("peers: unknown field %q",key)
  }
 }
 if err:=scanner.Err();err!=nil{return Peer{},err}
 if !seen["publickey"]||!seen["allowedips"] {return Peer{},errors.New("peers: missing PublicKey or AllowedIPs")}
 return p,nil
}

func Format(p Peer) string {
 return fmt.Sprintf("[Peer]\nPublicKey = %x\nAllowedIPs = %s\n",p.PublicKey,p.IP.String())
}

func Load(dir string) ([]Peer,error) {
 if dir=="" {return nil,nil}
 entries,err:=os.ReadDir(dir)
 if errors.Is(err,os.ErrNotExist){return nil,nil}
 if err!=nil{return nil,err}
 if len(entries)>1024{return nil,errors.New("peers: too many entries")}
 result:=make([]Peer,0,len(entries))
 seenIP:=make(map[netip.Addr]bool)
 seenPub:=make(map[[32]byte]bool)
 for _,entry:=range entries {
  name:=entry.Name()
  if !strings.HasSuffix(name,".conf"){continue}
  if !ValidName(strings.TrimSuffix(name,".conf")) {return nil,fmt.Errorf("peers: unsafe filename %q",name)}
  if entry.Type()&os.ModeSymlink!=0 || !entry.Type().IsRegular(){
   // Some filesystems report type zero: check via Lstat.
   info,e:=os.Lstat(filepath.Join(dir,name))
   if e!=nil{return nil,e}
   if !info.Mode().IsRegular(){return nil,fmt.Errorf("peers: not a regular file: %s",name)}
  }
  full:=filepath.Join(dir,name)
  f,err:=os.Open(full)
  if err!=nil{return nil,err}
  data,readErr:=io.ReadAll(io.LimitReader(f,4097))
  closeErr:=f.Close()
  if readErr!=nil{return nil,readErr}
  if closeErr!=nil{return nil,closeErr}
  p,err:=Parse(strings.TrimSuffix(name,".conf"),string(data))
  if err!=nil{return nil,fmt.Errorf("%s: %w",name,err)}
  if seenIP[p.IP]||seenPub[p.PublicKey]{return nil,fmt.Errorf("peers: duplicate identity or IP: %s",name)}
  seenIP[p.IP]=true;seenPub[p.PublicKey]=true
  result=append(result,p)
 }
 return result,nil
}

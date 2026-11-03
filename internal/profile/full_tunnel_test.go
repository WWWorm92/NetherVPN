package profile

import(
 "strings"
 "testing"
)

func TestSharedFullTunnelProfile(t *testing.T){
 base:="[Interface]\nName = nvpn0\nPrivateKey = "+strings.Repeat("a",64)+"\nAddress = 10.77.0.3/32\nMTU = 1280\nFullTunnel = true\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = "+strings.Repeat("b",64)+"\nEndpoint = 77.221.132.76:51830\nAllowedIPs = 10.77.0.1\n"
 p,err:=Parse(base)
 if err!=nil{t.Fatal(err)}
 if !p.FullTunnel||p.DNS!="1.1.1.1"||p.Address!="10.77.0.3/32"{t.Fatalf("%+v",p)}
 for _,broken:=range []string{
  strings.Replace(base,"FullTunnel = true","FullTunnel = mango",1),
  strings.Replace(base,"DNS = 1.1.1.1","DNS = example.com",1),
  strings.Replace(base,"77.221.132.76:51830","example.org:51830",1),
  strings.Replace(base,"10.77.0.3/32","fd77:77::3/128",1),
 }{if _,err:=Parse(broken);err==nil{t.Error("accepted invalid full tunnel profile")}}
}

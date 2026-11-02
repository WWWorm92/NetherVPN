package peers

import (
 "net/netip"
 "strings"
 "testing"
)

func TestParse(t *testing.T) {
 raw:="[Peer]\nPublicKey = "+strings.Repeat("a1",32)+"\nAllowedIPs = 10.77.0.3\n"
 p,err:=Parse("android",raw)
 if err!=nil {t.Fatal(err)}
 if p.IP!=netip.MustParseAddr("10.77.0.3")||p.Name!="android"{t.Fatal(p)}
 if _,err=Parse("../etc",raw);err==nil{t.Fatal("path traversal")}
 if _,err=Parse("android",raw+"AllowedIPs = 10.77.0.4\n");err==nil{t.Fatal("duplicate accepted")}
 if _,err=Parse("android",strings.Replace(raw,"10.77.0.3","10.77.0.0/24",1));err==nil{t.Fatal("prefix accepted")}
 if _,err=Parse("android",strings.Replace(raw,"[Peer]","[Interface]",1));err==nil{t.Fatal("wrong section")}
}

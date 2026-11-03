//go:build linux
package routing

import (
 "errors"
 "strings"
 "testing"
)

type fakeRunner struct{
 calls []string
 failContains string
 rules string
 routes string
}
func (f *fakeRunner) Run(name string,args ...string)(string,error){
 cmd:=name+" "+strings.Join(args," ")
 f.calls=append(f.calls,cmd)
 if f.failContains!=""&&strings.Contains(cmd,f.failContains){return "",errors.New("injected failure")}
 if strings.Contains(cmd,"rule show"){return f.rules,nil}
 if strings.Contains(cmd,"route show table"){return f.routes,nil}
 return "",nil
}
func TestFullTunnelPolicy(t *testing.T){
 c,err:=New("nvpn0","77.221.132.76","1.1.1.1");if err!=nil{t.Fatal(err)}
 f:=&fakeRunner{};c.Runner=f
 if err:=c.Prepare();err!=nil{t.Fatal(err)}
 if err:=c.Up();err!=nil{t.Fatal(err)}
 c.Down()
 if err:=c.Up();err!=nil{t.Fatal(err)}
 c.Close()
 joined:=strings.Join(f.calls,"\n")
 for _,need:=range []string{
 "ip -4 route add unreachable default table 51830 metric 4278",
 "ip -4 rule add pref 12100 to 77.221.132.76/32 lookup main",
 "ip -4 rule add pref 12101 lookup 51830",
 "ip -6 rule add pref 12101 lookup 51830",
 "ip -4 route add default dev nvpn0 table 51830 metric 10",
 "resolvectl domain nvpn0 ~.",
 "resolvectl revert nvpn0",
 "ip -4 rule del pref 12100",
 }{if !strings.Contains(joined,need){t.Errorf("missing %q\n%s",need,joined)}}
 if c.prepared{t.Fatal("controller not closed")}
}
func TestCollisionsAndFailClosed(t *testing.T){
 c,_:=New("nvpn0","77.221.132.76","1.1.1.1")
 f:=&fakeRunner{rules:"12101: from all lookup 51830"};c.Runner=f
 if err:=c.checkFree();!errors.Is(err,errExisting){t.Fatalf("expected conflict: %v",err)}
 f.rules="";f.failContains="resolvectl domain"
 if err:=c.Prepare();err!=nil{t.Fatal(err)}
 if err:=c.Up();err==nil{t.Fatal("DNS failure accepted")}
 joined:=strings.Join(f.calls,"\n")
 if !strings.Contains(joined,"ip -4 route del default dev nvpn0 table 51830 metric 10"){t.Fatal("failed to roll back failed DNS")}
 if !c.prepared{t.Fatal("fail-closed guard removed after DNS failure")}
 c.Close()
}
func TestInvalidConfig(t *testing.T){
 for _,v:=range []struct{iface,host,dns string}{
 {"../tun","1.2.3.4","1.1.1.1"},
 {"nvpn0","example.com","1.1.1.1"},
 {"nvpn0","127.0.0.1","1.1.1.1"},
 {"nvpn0","1.2.3.4","example.com"},
 }{if _,err:=New(v.iface,v.host,v.dns);err==nil{t.Fatalf("accepted %+v",v)}}
}

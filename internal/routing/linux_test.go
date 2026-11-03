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
 routeGet string
 missing4 bool
 missing6 bool
 showError string
}
func (f *fakeRunner) Run(name string,args ...string)(string,error){
 cmd:=name+" "+strings.Join(args," ")
 f.calls=append(f.calls,cmd)
 if f.failContains!=""&&strings.Contains(cmd,f.failContains){return "",errors.New("injected failure")}
 if strings.Contains(cmd,"rule show"){return f.rules,nil}
 if strings.Contains(cmd,"route get"){
  if f.routeGet!=""{return f.routeGet,nil}
  return "1.1.1.1 dev nvpn0 src 10.77.0.3",nil
 }
 if strings.Contains(cmd,"route show table"){
  if f.showError!=""{return f.showError,errors.New("ip exit status 2")}
  if strings.Contains(cmd," -4 ")&&f.missing4 {
   return "Error: ipv4: FIB table does not exist.\nDump terminated",errors.New("ip exit status 2")
  }
  if strings.Contains(cmd," -6 ")&&f.missing6 {
   return "Error: ipv6: FIB table does not exist.\nDump terminated",errors.New("ip exit status 2")
  }
  return f.routes,nil
 }
 return "",nil
}
func TestFullTunnelPolicy(t *testing.T){
 c,err:=New("nvpn0","77.221.132.76","1.1.1.1");if err!=nil{t.Fatal(err)}
 f:=&fakeRunner{};c.Runner=f
 if err:=c.SetLocalAddress("10.77.0.3/32");err!=nil{t.Fatal(err)}
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
 "ip -4 route add default dev nvpn0 table 51830 metric 10 src 10.77.0.3",
 "ip -4 route get 1.1.1.1",
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

func TestRejectWrongSelectedRoute(t *testing.T){
 c,_:=New("nvpn0","77.221.132.76","1.1.1.1")
 f:=&fakeRunner{routeGet:"1.1.1.1 via 192.168.1.1 dev wlan0"}
 c.Runner=f
 if err:=c.Prepare();err!=nil{t.Fatal(err)}
 if err:=c.Up();err==nil{t.Fatal("must not claim Full Tunnel with wrong route")}
 if c.linked{t.Fatal("route was incorrectly activated")}
 if !c.prepared{t.Fatal("fail-close guard must remain active on wrong route")}
 c.Close()
}

func TestSetLocalAddress(t *testing.T){
 c,_:=New("nvpn0","77.221.132.76","1.1.1.1")
 if err:=c.SetLocalAddress("10.77.0.3/32");err!=nil{t.Fatal(err)}
 if c.Source.String()!="10.77.0.3"{t.Fatal(c.Source)}
 for _,raw:=range []string{"", "fe80::2/64", "0.0.0.0/32", "10.77.0.3"}{
  if err:=c.SetLocalAddress(raw);err==nil{t.Errorf("accepted %q",raw)}
 }
}

func TestUninitializedRoutingTableIsFree(t *testing.T){
 c,err:=New("nvpn0","77.221.132.76","1.1.1.1")
 if err!=nil{t.Fatal(err)}
 f:=&fakeRunner{missing4:true,missing6:true}
 c.Runner=f
 // Regression: on a fresh Linux machine 'ip route show table 51830'
 // fails with status 2 instead of returning an empty list.
 if err:=c.Prepare();err!=nil{t.Fatalf("missing FIB must not block initial Full Tunnel: %v",err)}
 if err:=c.Up();err!=nil{t.Fatalf("should configure VPN route after table allocation: %v",err)}
 c.Close()
 observed:=strings.Join(f.calls,"\n")
 for _,expected:=range []string{
  "ip -4 route show table 51830",
  "ip -6 route show table 51830",
  "ip -4 route add unreachable default table 51830 metric 4278",
  "ip -6 route add unreachable default table 51830 metric 4278",
 }{
  if !strings.Contains(observed,expected){t.Errorf("missing command %q",expected)}
 }
}

func TestUninitializedFIBRecognizesOnlyExactError(t *testing.T){
 good:=[]string{
  "Error: ipv4: FIB table does not exist.\nDump terminated\n",
  "Error: ipv6: FIB table does not exist.\nDump terminated",
  "Error: FIB table does not exist.",
 }
 for _,message:=range good{
  if !missingFIBTable(message){t.Errorf("did not recognize missing FIB: %q",message)}
 }
 bad:=[]string{
  "",
  "Error: Operation not permitted",
  "Error: ipv4: FIB table does not exist.\nOperation not permitted",
  "unrelated error mentions FIB table does not exist.",
  "Error: ipv6: FIB table does not exist.\nError: netlink failed",
 }
 for _,message:=range bad{
  if missingFIBTable(message){t.Errorf("incorrectly ignored error: %q",message)}
 }
}

func TestRoutingCheckDoesNotIgnoreOtherFailures(t *testing.T){
 c,_:=New("nvpn0","77.221.132.76","1.1.1.1")
 f:=&fakeRunner{showError:"Error: Operation not permitted"}
 c.Runner=f
 if err:=c.Prepare();err==nil{t.Fatal("netlink permission error was ignored")}
 for _,cmd:=range f.calls{
  if strings.Contains(cmd,"route add")||strings.Contains(cmd,"rule add"){
   t.Fatalf("changed routes despite failed preflight: %v",f.calls)
  }
 }
}

func TestRoutingCheckRejectsExistingTable(t *testing.T){
 c,_:=New("nvpn0","77.221.132.76","1.1.1.1")
 f:=&fakeRunner{routes:"default via 192.0.2.1 dev eth0"}
 c.Runner=f
 if err:=c.checkFree();!errors.Is(err,errExisting){t.Fatalf("table ownership collision not rejected: %v",err)}
}

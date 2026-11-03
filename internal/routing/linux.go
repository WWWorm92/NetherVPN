//go:build linux

// Package routing installs a separate fail-closed Linux policy routing table.
// It does not replace the host main default route. The host's own Internet
// routes remain untouched, while rule priority 12101 diverts local traffic to
// a tunnel route (or an unreachable fallback when the tunnel disappears).
package routing

import (
 "errors"
 "fmt"
 "net/netip"
 "os/exec"
 "regexp"
 "strings"

 "github.com/WWWorm92/NetherVPN/internal/tun"
)

const Table = "51830"
const ExemptPriority = "12100"
const VPNPriority = "12101"

// Runner is replaceable by an in-memory command simulator for unit tests.
type Runner interface { Run(name string,args ...string)(string,error) }
type executable struct {}
func (executable) Run(name string,args ...string)(string,error){
 b,e:=exec.Command(name,args...).CombinedOutput()
 if e!=nil{return string(b),fmt.Errorf("%s %s: %w: %s",name,strings.Join(args," "),e,strings.TrimSpace(string(b)))}
 return string(b),nil
}

type command struct {name string; args []string}
func ip(args ...string)command{return command{"ip",args}}
func dns(args ...string)command{return command{"resolvectl",args}}

type Controller struct {
 Runner Runner
 Interface string
 Endpoint netip.Addr
 DNS netip.Addr
 undo []command
 prepared bool
 linked bool
}

var errExisting = errors.New("routing: reserved policy table/priority already in use; refusing to overwrite other routing rules")

func New(iface, endpoint, resolver string)(*Controller,error){
 if !tun.ValidName(iface){return nil,errors.New("routing: invalid VPN interface")}
 addr,err:=netip.ParseAddr(endpoint)
 if err!=nil||!addr.Is4()||addr.IsUnspecified()||addr.IsLoopback(){return nil,errors.New("routing: full tunnel requires numeric public IPv4 server Endpoint")}
 dnsIP,err:=netip.ParseAddr(resolver)
 if err!=nil||!dnsIP.Is4()||dnsIP.IsUnspecified(){return nil,errors.New("routing: DNS must be numeric IPv4")}
 return &Controller{Runner:executable{},Interface:iface,Endpoint:addr,DNS:dnsIP},nil
}
func (c *Controller) run(cmd command)error{_,err:=c.Runner.Run(cmd.name,cmd.args...);return err}
func (c *Controller) value(cmd command)(string,error){return c.Runner.Run(cmd.name,cmd.args...)}

func (c *Controller) checkFree()error{
 for _,family:=range []string{"-4","-6"} {
  rules,err:=c.value(ip(family,"rule","show"))
  if err!=nil{return err}
  for _,line:=range strings.Split(rules,"\n"){
   line=strings.TrimSpace(line)
   if strings.HasPrefix(line,ExemptPriority+":")||strings.HasPrefix(line,VPNPriority+":"){return errExisting}
  }
  routes,err:=c.value(ip(family,"route","show","table",Table))
  if err!=nil{return err}
  if strings.TrimSpace(routes)!=""{return errExisting}
 }
 return nil
}

// Prepare verifies prerequisites before touching the kernel, then installs
// unreachable routes before diverting traffic. Only the pinned UDP endpoint
// can use the host's main routing table; IPv6 egress is unreachable.
func (c *Controller) Prepare()error{
 if c.prepared{return errors.New("routing: already prepared")}
 for _,tool:=range []string{"ip","resolvectl"}{
  if _,err:=exec.LookPath(tool);err!=nil{return fmt.Errorf("routing: required tool %s unavailable: %w",tool,err)}
 }
 if err:=c.checkFree();err!=nil{return err}
 steps:=[]struct{do,undo command}{
  {ip("-4","route","add","unreachable","default","table",Table,"metric","4278"),
   ip("-4","route","del","unreachable","default","table",Table,"metric","4278")},
  {ip("-6","route","add","unreachable","default","table",Table,"metric","4278"),
   ip("-6","route","del","unreachable","default","table",Table,"metric","4278")},
  {ip("-4","rule","add","pref",ExemptPriority,"to",c.Endpoint.String()+"/32","lookup","main"),
   ip("-4","rule","del","pref",ExemptPriority)},
  {ip("-4","rule","add","pref",VPNPriority,"lookup",Table),
   ip("-4","rule","del","pref",VPNPriority)},
  {ip("-6","rule","add","pref",VPNPriority,"lookup",Table),
   ip("-6","rule","del","pref",VPNPriority)},
 }
 for _,step:=range steps{
  if err:=c.run(step.do);err!=nil{
   c.rollback()
   return fmt.Errorf("routing: failed to activate fail-closed policy: %w",err)
  }
  c.undo=append(c.undo,step.undo)
 }
 c.prepared=true
 return nil
}

// Up switches fail-closed IPv4 from unreachable to active TUN, then registers
// a DNS routing domain with systemd-resolved. On failure it reverts the TUN
// route, leaving the unreachable fallback in place.
func (c *Controller) Up()error{
 if !c.prepared{return errors.New("routing: not prepared")}
 if c.linked{return nil}
 if err:=c.run(ip("-4","route","add","default","dev",c.Interface,"table",Table,"metric","10"));err!=nil{return err}
 if err:=c.run(dns("dns",c.Interface,c.DNS.String()));err!=nil{
  c.Down();return fmt.Errorf("routing: cannot configure DNS: %w",err)
 }
 if err:=c.run(dns("domain",c.Interface,"~."));err!=nil{
  c.Down();return fmt.Errorf("routing: cannot configure DNS routing: %w",err)
 }
 if err:=c.run(dns("default-route",c.Interface,"yes"));err!=nil{
  c.Down();return fmt.Errorf("routing: cannot set DNS default route: %w",err)
 }
 c.linked=true
 return nil
}

func (c *Controller) Down(){
 if !c.prepared{return}
 // DNS can already be gone because the interface was removed during reconnect.
 _=c.run(dns("revert",c.Interface))
 _=c.run(ip("-4","route","del","default","dev",c.Interface,"table",Table,"metric","10"))
 c.linked=false
}

func (c *Controller) rollback(){
 for i:=len(c.undo)-1;i>=0;i--{_ = c.run(c.undo[i])}
 c.undo=nil
 c.prepared=false
}
func (c *Controller) Close(){
 if !c.prepared{return}
 c.Down()
 c.rollback()
}

// PolicyConflict can be used by a future recovery tool without deleting any
// unknown network configuration.
func PolicyConflict() *regexp.Regexp {return regexp.MustCompile(`(?m)^\s*(12100|12101):`)}

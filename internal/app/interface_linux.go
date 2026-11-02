//go:build linux
package app
import ("fmt";"net/netip";"os/exec";"github.com/WWWorm92/NetherVPN/internal/tun")
func configureInterface(d *tun.Device, address string, mtu int)error{
 if address==""{return nil}
 prefix,err:=netip.ParsePrefix(address);if err!=nil{return err}
 commands:=[][]string{{"addr","replace",prefix.String(),"dev",d.Name()},{"link","set","dev",d.Name(),"mtu",fmt.Sprint(mtu),"up"}}
 for _,args:=range commands{out,err:=exec.Command("ip",args...).CombinedOutput();if err!=nil{return fmt.Errorf("interface setup: %w: %s",err,out)}}
 return nil
}

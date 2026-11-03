package main

import (
 "bufio"
 "fmt"
 "io"
 "net"
 "os"
 "os/exec"
 "strings"

 "github.com/WWWorm92/NetherVPN/internal/profile"
)

type provisionOptions struct {
 Name string
 Endpoint string
 DNS string
 Platform string
 FullTunnel bool
}

// PublicIPDefault checks the address selected by the kernel for outbound IPv4.
// It does not send packets and MUST NOT be mistaken for the public IP behind NAT.
func publicIPDefault()string{
 conn,e:=net.Dial("udp4","1.1.1.1:53")
 if e!=nil{return ""}
 defer conn.Close()
 addr,ok:=conn.LocalAddr().(*net.UDPAddr)
 if !ok||addr.IP==nil||addr.IP.IsLoopback()||addr.IP.IsPrivate()||addr.IP.IsUnspecified(){return ""}
 return addr.IP.String()
}

func ask(reader *bufio.Reader, out io.Writer, label, fallback string)(string,error){
 if fallback!=""{fmt.Fprintf(out,"%s [%s]: ",label,fallback)} else {fmt.Fprintf(out,"%s: ",label)}
 line,e:=reader.ReadString('\n')
 if e!=nil && !(e==io.EOF&&line!=""){return "",e}
 val:=strings.TrimSpace(line)
 if val==""{val=fallback}
 return val,nil
}

// Wizard collects only configuration choices. The caller performs the
// filesystem mutation and the optional, explicitly confirmed service restart.
func wizard(in io.Reader,out io.Writer,config string,defaults provisionOptions)(provisionOptions,bool,error){
 reader:=bufio.NewReader(in)
 fmt.Fprintln(out,"")
 fmt.Fprintln(out,"╔════════ NetherVPN · мастер создания клиента ════════╗")
 fmt.Fprintln(out,"Ключи будут созданы автоматически и сохранены с правами 0600.")
 var opts=defaults
 if opts.Name==""{
  for {
   name,e:=ask(reader,out,"Имя устройства (латиница, цифры, - или _)","android")
   if e!=nil{return opts,false,e}
   if peersNameValid(name){opts.Name=name;break}
   fmt.Fprintln(out,"Недопустимое имя: используйте 1–48 символов [A-Za-z0-9_-].")
  }
 }
 if opts.Platform==""{
  for {
   value,e:=ask(reader,out,"Тип устройства: 1 — Android, 2 — Linux","1")
   if e!=nil{return opts,false,e}
   switch strings.ToLower(value){
   case "1","android":opts.Platform="android"
   case "2","linux":opts.Platform="linux"
   default:fmt.Fprintln(out,"Введите 1 или 2.");continue
   }
   break
  }
 }
 if opts.Platform!="android"&&opts.Platform!="linux"{return opts,false,fmt.Errorf("unknown platform: %s",opts.Platform)}
 for {
  value,e:=ask(reader,out,"Режим: 1 — весь IPv4 через VPN, 2 — только VPN-сеть","1")
  if e!=nil{return opts,false,e}
  switch strings.ToLower(value){
  case "1","full","yes":opts.FullTunnel=true
  case "2","split","no":opts.FullTunnel=false
  default:fmt.Fprintln(out,"Введите 1 или 2.");continue
  }
  break
 }
 if opts.Endpoint==""{
  configData,err:=profile.Load(config)
  if err!=nil{return opts,false,fmt.Errorf("server profile: %w",err)}
  port:=configData.ListenPort
  if port==""{port="51830"}
  ip:=publicIPDefault()
  candidate:=""
  if ip!=""{candidate=net.JoinHostPort(ip,port)}
  for{
   value,e:=ask(reader,out,"Публичный IPv4 сервера и UDP-порт",candidate)
   if e!=nil{return opts,false,e}
   if err=validateEndpoint(value);err==nil{opts.Endpoint=value;break}
   fmt.Fprintln(out,"Введите доступный извне числовой IPv4:порт (например, 77.221.132.76:51830).")
  }
 }
 if opts.FullTunnel {
  if opts.DNS==""{opts.DNS="1.1.1.1"}
  for {
   value,e:=ask(reader,out,"DNS-сервер внутри VPN",opts.DNS)
   if e!=nil{return opts,false,e}
   ip:=net.ParseIP(value)
   if ip!=nil&&ip.To4()!=nil&&!ip.IsUnspecified(){opts.DNS=value;break}
   fmt.Fprintln(out,"Нужен числовой IPv4 DNS-сервера.")
  }
 } else if opts.DNS=="" {opts.DNS="1.1.1.1"}
 fmt.Fprintf(out,"\nУстройство: %s (%s)\nСервер: %s\nРежим: %s\n",
  opts.Name,opts.Platform,opts.Endpoint,map[bool]string{true:"Полный IPv4 + DNS",false:"Только адрес VPN-сервера"}[opts.FullTunnel])
 confirm,e:=ask(reader,out,"Создать профиль и зарегистрировать публичный ключ? да/нет","да")
 if e!=nil{return opts,false,e}
 if confirm!="да"&&strings.ToLower(confirm)!="yes"&&strings.ToLower(confirm)!="y"{return opts,false,fmt.Errorf("creation cancelled")}
 return opts,true,nil
}

func peersNameValid(name string)bool{
 if len(name)<1||len(name)>48{return false}
 for _,ch:=range name{
  if !(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'||ch=='-'||ch=='_'){return false}
 }
 return true
}

func askRestart(in io.Reader,out io.Writer){
 reader:=bufio.NewReader(in)
 fmt.Fprintln(out,"Важно: применение нового клиента кратковременно отключит действующие VPN-сессии.")
 answer,e:=ask(reader,out,"Перезапустить службу nethervpn-server сейчас? да/нет","да")
 if e!=nil{fmt.Fprintln(out,"Перезапуск не выполнен. Вручную: sudo systemctl restart nethervpn-server");return}
 if answer!="да"&&strings.ToLower(answer)!="yes"&&strings.ToLower(answer)!="y"{
  fmt.Fprintln(out,"Клиент зарегистрирован. Для активации: sudo systemctl restart nethervpn-server")
  return
 }
 cmd:=exec.Command("systemctl","restart","nethervpn-server")
 if output,e:=cmd.CombinedOutput();e!=nil{
  fmt.Fprintf(os.Stderr,"Не удалось перезапустить службу: %v %s\n",e,strings.TrimSpace(string(output)))
 }else{fmt.Fprintln(out,"Сервер перезапущен. Клиент активирован.")}
}

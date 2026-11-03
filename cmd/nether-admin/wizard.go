package main

import (
 "bufio"
 "fmt"
 "io"
 "net"
 "os"
 "os/exec"
 "strings"

 "github.com/WWWorm92/NetherVPN/internal/peers"
 "github.com/WWWorm92/NetherVPN/internal/profile"
)

type clientOptions struct{Name,Endpoint string}
func ask(r *bufio.Reader,out io.Writer,label,def string)(string,error){
 if def!=""{fmt.Fprintf(out,"%s [%s]: ",label,def)}else{fmt.Fprintf(out,"%s: ",label)}
 value,err:=r.ReadString('\n')
 if err!=nil&&!(err==io.EOF&&value!=""){return "",err}
 value=strings.TrimSpace(value);if value==""{value=def}
 return value,nil
}
func defaultEndpoint(config string)string{
 cfg,err:=profile.Load(config)
 if err!=nil{return ""}
 port:=cfg.ListenPort;if port==""{port="51830"}
 if cfg.ListenAddress!=""&&cfg.ListenAddress!="0.0.0.0"{
  ip:=net.ParseIP(cfg.ListenAddress)
  if ip!=nil&&ip.To4()!=nil&&!ip.IsPrivate()&&!ip.IsLoopback(){return net.JoinHostPort(cfg.ListenAddress,port)}
 }
 // Kernel-selected source address may not equal the public address behind NAT.
 conn,err:=net.Dial("udp4","1.1.1.1:53")
 if err!=nil{return ""}
 defer conn.Close()
 addr,ok:=conn.LocalAddr().(*net.UDPAddr)
 if ok&&addr.IP!=nil&&!addr.IP.IsPrivate()&&!addr.IP.IsLoopback(){return net.JoinHostPort(addr.IP.String(),port)}
 return ""
}
func askClient(r *bufio.Reader,out io.Writer,config,name,endpoint string)(clientOptions,error){
 opts:=clientOptions{Name:name,Endpoint:endpoint}
 for opts.Name=="" {
  value,err:=ask(r,out,"Имя клиента (латиница, цифры, - или _)","")
  if err!=nil{return opts,err}
  if peers.ValidName(value){opts.Name=value}else{fmt.Fprintln(out,"Некорректное имя")}
 }
 for opts.Endpoint==""{
  value,err:=ask(r,out,"Публичный IPv4 сервера:UDP-порт",defaultEndpoint(config))
  if err!=nil{return opts,err}
  if err=validateEndpoint(value);err==nil{opts.Endpoint=value}else{fmt.Fprintln(out,"Нужен IPv4:порт, доступный извне")}
 }
 fmt.Fprintf(out,"\nИмя: %s\nСервер: %s\nФайл будет одинаковым для Android и Linux.\n",opts.Name,opts.Endpoint)
 ok,err:=ask(r,out,"Создать клиента? (да/нет)","да")
 if err!=nil{return opts,err}
 if !accepted(ok){return opts,fmt.Errorf("создание отменено")}
 return opts,nil
}
func accepted(s string)bool{
 return s=="да"||strings.EqualFold(s,"yes")||strings.EqualFold(s,"y")
}
func askRestart(r *bufio.Reader,out io.Writer){
 fmt.Fprintln(out,"Перезапуск кратковременно отключит действующие VPN-сессии.")
 value,err:=ask(r,out,"Активировать изменения перезапуском nethervpn-server? (да/нет)","да")
 if err!=nil||!accepted(value){
  fmt.Fprintln(out,"Для применения: sudo systemctl restart nethervpn-server")
  return
 }
 output,err:=exec.Command("systemctl","restart","nethervpn-server").CombinedOutput()
 if err!=nil{fmt.Fprintf(out,"Ошибка перезапуска: %v %s\n",err,output)}else{fmt.Fprintln(out,"Изменения применены")}
}
func runMenu(r *bufio.Reader,out io.Writer,config,dir,outputDir string)error{
 for{
  fmt.Fprintln(out,"\n╔══════ NetherVPN · управление сервером ══════╗")
  fmt.Fprintln(out,"  1. Создать клиента")
  fmt.Fprintln(out,"  2. Список клиентов")
  fmt.Fprintln(out,"  3. Удалить клиента")
  fmt.Fprintln(out,"  4. Информация о сервере")
  fmt.Fprintln(out,"  5. Выход")
  choice,err:=ask(r,out,"Выберите действие","5")
  if err!=nil{return err}
  switch choice{
  case "1":
   opts,err:=askClient(r,out,config,"","")
   if err!=nil{fmt.Fprintf(out,"%v\n",err);continue}
   if err=create(config,dir,outputDir,opts.Name,opts.Endpoint);err!=nil{fmt.Fprintf(out,"Ошибка: %v\n",err);continue}
   askRestart(r,out)
  case "2":
   if err:=listClients(out,dir);err!=nil{fmt.Fprintf(out,"Ошибка: %v\n",err)}
  case "3":
   name,err:=ask(r,out,"Имя клиента для удаления","")
   if err!=nil{return err}
   if !peers.ValidName(name){fmt.Fprintln(out,"Некорректное имя");continue}
   confirmation,err:=ask(r,out,"Отозвать ключ и удалить экспорт? (да/нет)","нет")
   if err!=nil{return err}
   if !accepted(confirmation){fmt.Fprintln(out,"Отменено");continue}
   if err:=revoke(dir,outputDir,name);err!=nil{fmt.Fprintf(out,"Ошибка: %v\n",err);continue}
   fmt.Fprintf(out,"Клиент %s отозван\n",name)
   askRestart(r,out)
  case "4":
   cfg,err:=profile.Load(config)
   if err!=nil{fmt.Fprintf(out,"Ошибка чтения конфигурации: %v\n",err);continue}
   fmt.Fprintf(out,"Интерфейс: %s\nVPN адрес: %s\nUDP-порт: %s\nПриватный ключ не отображается.\n",cfg.Interface,cfg.Address,cfg.ListenPort)
  case "5","0":return nil
  default:fmt.Fprintln(out,"Неизвестное действие")
  }
 }
}

using System;
using System.Net;
using System.Net.Sockets;
using System.IO;

// Compile two differently named copies. No explicit proxy: routing must come from the Windows adapter.
class WindowsNetworkProbe {
    static int Main(string[] args) {
        try {
            if(args.Length != 1) throw new ArgumentException("tcp or udp");
            if(args[0] == "tcp") {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                var request=(HttpWebRequest)WebRequest.Create("https://example.com/");
                request.Proxy=null;request.Timeout=15000;request.ReadWriteTimeout=15000;
                using(var response=request.GetResponse()) using(var reader=new StreamReader(response.GetResponseStream())) {
                    string body=reader.ReadToEnd();if(!body.Contains("Example Domain")) throw new IOException("Unexpected HTTPS response");
                    Console.WriteLine("HTTPS response verified");
                }
            } else if(args[0] == "udp") {
                // STUN application datagram; the upstream router explicitly bypasses DNS port 53.
                byte[] query={0,1,0,0,0x21,0x12,0xa4,0x42,1,2,3,4,5,6,7,8,9,10,11,12};
                using(var udp=new UdpClient(AddressFamily.InterNetwork)) {
                    udp.Client.ReceiveTimeout=15000;udp.Connect("stun.l.google.com",19302);udp.Send(query,query.Length);
                    IPEndPoint sender=null;byte[] response=udp.Receive(ref sender);
                    if(response.Length<20 || response[0]!=1 || response[1]!=1 || response[4]!=0x21 || response[7]!=0x42)throw new IOException("Unexpected UDP response");
                    Console.WriteLine("Application UDP response verified");
                }
            } else throw new ArgumentException("tcp or udp");
            return 0;
        }catch(Exception e){Console.Error.WriteLine(e.GetType().Name+": "+e.Message);return 1;}
    }
}

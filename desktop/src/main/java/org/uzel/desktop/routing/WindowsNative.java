package org.uzel.desktop.routing;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small native bridge: authoritative OS directories and a mutually identified local pipe. */
public final class WindowsNative {
    private WindowsNative(){}
    interface Kernel extends StdCallLibrary {
        Kernel API=Native.load("kernel32",Kernel.class);
        int GetSystemDirectoryW(char[] buffer,int length);
        Pointer CreateFileW(WString path,int access,int sharing,Pointer security,int disposition,int flags,Pointer template);
        boolean GetNamedPipeServerProcessId(Pointer pipe,IntByReference pid);
        boolean PeekNamedPipe(Pointer pipe,Pointer data,int size,Pointer read,IntByReference available,Pointer remaining);
        boolean ReadFile(Pointer file,byte[] data,int length,IntByReference read,Pointer overlapped);
        boolean WriteFile(Pointer file,byte[] data,int length,IntByReference written,Pointer overlapped);
        boolean CloseHandle(Pointer handle);
        Pointer OpenProcess(int access,boolean inherit,int pid);
        boolean GetProcessTimes(Pointer process,Pointer creation,Pointer exit,Pointer kernel,Pointer user);
        boolean GetExitCodeProcess(Pointer process,IntByReference exitCode);
    }
    interface Shell extends StdCallLibrary {
        Shell API=Native.load("shell32",Shell.class);
        int SHGetFolderPathW(Pointer owner,int folder,Pointer token,int flags,char[] path);
    }
    interface Security extends StdCallLibrary {
        Security API=Native.load("advapi32",Security.class);
        boolean OpenProcessToken(Pointer process,int access,PointerByReference token);
        boolean GetTokenInformation(Pointer token,int kind,Pointer data,int length,IntByReference required);
    }
    public static Path programFiles()throws IOException{
        char[] buffer=new char[32768];
        if(Shell.API.SHGetFolderPathW(null,0x26,null,0,buffer)!=0)throw new IOException("Windows Program Files unavailable");
        return Path.of(Native.toString(buffer));
    }
    public static Path powershell()throws IOException{
        char[] buffer=new char[32768];int count=Kernel.API.GetSystemDirectoryW(buffer,buffer.length);
        if(count==0 || count>=buffer.length)throw new IOException("Windows system directory unavailable");
        return Path.of(Native.toString(buffer),"WindowsPowerShell","v1.0","powershell.exe");
    }
    public static boolean elevated(long pid){
        Pointer process=Kernel.API.OpenProcess(0x1000,false,(int)pid);
        if(process==null)return false;
        try{return elevatedProcess(process);}finally{Kernel.API.CloseHandle(process);}
    }
    private static boolean elevatedProcess(Pointer process){
        PointerByReference token=new PointerByReference();
        if(!Security.API.OpenProcessToken(process,8,token))return false;
        try(Memory value=new Memory(4)){
            return Security.API.GetTokenInformation(token.getValue(),20,value,4,new IntByReference()) && value.getInt(0)!=0;
        }finally{Kernel.API.CloseHandle(token.getValue());}
    }
    /** Uses PROCESS_QUERY_LIMITED_INFORMATION, which also permits querying an elevated same-user broker. */
    public static long processCreatedMillis(long pid)throws IOException{
        Pointer process=Kernel.API.OpenProcess(0x1000,false,(int)pid);
        if(process==null)throw new IOException("Routing process identity unavailable");
        try{return liveCreationMillis(process);}finally{Kernel.API.CloseHandle(process);}
    }
    private static long liveCreationMillis(Pointer process)throws IOException{
        IntByReference exitCode=new IntByReference();
        if(!Kernel.API.GetExitCodeProcess(process,exitCode) || exitCode.getValue()!=259)
            throw new IOException("Routing broker exited");
        try(Memory creation=new Memory(8);Memory exit=new Memory(8);Memory kernel=new Memory(8);Memory user=new Memory(8)){
            if(!Kernel.API.GetProcessTimes(process,creation,exit,kernel,user))throw new IOException("Routing broker creation identity unavailable");
            // FILETIME counts 100ns intervals since 1601-01-01; Java timestamps use milliseconds since 1970-01-01.
            return creation.getLong(0)/10_000L-11_644_473_600_000L;
        }
    }
    public static Pipe openPipe(String name,long expectedPid,long expectedCreatedMs)throws IOException{
        Pointer handle=Kernel.API.CreateFileW(new WString("\\\\.\\pipe\\"+name),0xC0000000,0,null,3,0x110000,null);
        if(handle==null || Pointer.nativeValue(handle)==-1L)throw new FileNotFoundException("Routing pipe unavailable");
        try{
            IntByReference server=new IntByReference();
            if(!Kernel.API.GetNamedPipeServerProcessId(handle,server) || Integer.toUnsignedLong(server.getValue())!=expectedPid)
                throw new IOException("Routing broker identity mismatch");
            Pointer broker=Kernel.API.OpenProcess(0x1000,false,(int)expectedPid);
            if(broker==null)throw new IOException("Routing broker identity unavailable");
            try{
                if(liveCreationMillis(broker)!=expectedCreatedMs || !elevatedProcess(broker))
                    throw new IOException("Routing broker creation or elevation identity mismatch");
            }finally{Kernel.API.CloseHandle(broker);}
            return new Pipe(handle);
        }catch(IOException|RuntimeException failure){Kernel.API.CloseHandle(handle);throw failure;}
    }
    public static final class Pipe implements Closeable {
        private final Pointer handle;private final AtomicBoolean closed=new AtomicBoolean();
        private Pipe(Pointer handle){this.handle=handle;}
        public InputStream input(){return new InputStream(){
            @Override public int read()throws IOException{byte[] data=new byte[1];return read(data,0,1)<0?-1:data[0]&255;}
            @Override public int read(byte[] target,int offset,int length)throws IOException{
                if(length==0)return 0;
                while(!closed.get()){
                    IntByReference available=new IntByReference();
                    if(!Kernel.API.PeekNamedPipe(handle,null,0,null,available,null))return -1;
                    if(available.getValue()>0){
                        byte[] bytes=new byte[Math.min(length,available.getValue())];IntByReference read=new IntByReference();
                        if(!Kernel.API.ReadFile(handle,bytes,bytes.length,read,null))return -1;
                        System.arraycopy(bytes,0,target,offset,read.getValue());return read.getValue();
                    }
                    try{Thread.sleep(25);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new InterruptedIOException("Routing read cancelled");}
                }
                return -1;
            }
        };}
        public OutputStream output(){return new OutputStream(){
            @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
            @Override public void write(byte[] data,int offset,int length)throws IOException{
                if(closed.get())throw new IOException("Routing pipe closed");
                byte[] bytes=java.util.Arrays.copyOfRange(data,offset,offset+length);IntByReference written=new IntByReference();
                if(!Kernel.API.WriteFile(handle,bytes,length,written,null) || written.getValue()!=length)throw new IOException("Routing pipe write failed");
            }
        };}
        @Override public void close(){if(closed.compareAndSet(false,true))Kernel.API.CloseHandle(handle);}
    }
}

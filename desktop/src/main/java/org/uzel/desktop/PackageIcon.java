package org.uzel.desktop;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Build-time ICO wrapping the same original node mark used by the window and tray. */
public final class PackageIcon {
    public static void main(String[] args)throws IOException {
        Path path=Path.of(args[0]);Files.createDirectories(path.getParent());
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(DesktopMain.icon(),"png",png);
        byte[] data=png.toByteArray();
        ByteBuffer header=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short)0).putShort((short)1).putShort((short)1);
        header.put((byte)48).put((byte)48).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(data.length).putInt(22);
        try(OutputStream out=Files.newOutputStream(path)){out.write(header.array());out.write(data);}
    }
}

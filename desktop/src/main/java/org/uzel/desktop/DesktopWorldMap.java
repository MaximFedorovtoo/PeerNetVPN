package org.uzel.desktop;

import java.awt.*;
import java.awt.geom.*;
import java.io.*;

/** Java2D port of Android NetworkMapView. Exactly the same PNM1 offline asset and projection. */
final class DesktopWorldMap {
    private static final Path2D land=new Path2D.Double(Path2D.WIND_EVEN_ODD),borders=new Path2D.Double(),grid=new Path2D.Double();
    private static final Path2D[] regions=new Path2D[7];
    private static boolean available;
    static {
        for(int i=0;i<7;i++)regions[i]=new Path2D.Double(Path2D.WIND_EVEN_ODD);
        try(InputStream resource=DesktopWorldMap.class.getResourceAsStream("/world-countries-110m.bin")) {
            if(resource==null)throw new IOException("Offline map missing");
            DataInputStream input=new DataInputStream(new BufferedInputStream(resource));
            if(input.readInt()!=0x504e4d31)throw new IOException("Map version");
            int countries=input.readUnsignedShort();if(countries>300)throw new IOException("Map bounds");
            for(int c=0;c<countries;c++) {
                String code=""+(char)input.readUnsignedByte()+(char)input.readUnsignedByte();int rings=input.readUnsignedShort();
                Path2D country=new Path2D.Double(Path2D.WIND_EVEN_ODD);
                for(int r=0;r<rings;r++){int points=input.readUnsignedShort();for(int p=0;p<points;p++){double x=input.readUnsignedShort()/16.0,y=input.readUnsignedShort()/16.0;if(p==0)country.moveTo(x,y);else country.lineTo(x,y);}country.closePath();}
                land.append(country,false);borders.append(country,false);regions[DesktopRegions.group(code)].append(country,false);
            }
            for(int lon=-180;lon<=180;lon+=30)for(int lat=-60;lat<=85;lat+=2){if(lat==-60)grid.moveTo(x(lon,lat),y(lat));else grid.lineTo(x(lon,lat),y(lat));}
            for(int lat=-60;lat<=80;lat+=20){grid.moveTo(x(-180,lat),y(lat));grid.lineTo(x(180,lat),y(lat));}
            available=true;
        }catch(IOException ignored){available=false;}
    }
    private static double x(double lon,double lat){return 500+lon*(460.0/180)*(.86+.14*Math.cos(Math.toRadians(lat)));}
    private static double y(double lat){return 254-lat*2.6;}
    static boolean available(){return available;}
    static void paint(Graphics2D source,int left,int top,int width,int height,int[] counts,int selected) {
        Graphics2D g=(Graphics2D)source.create();g.clip(new Rectangle(left,top,width,height));
        g.setPaint(new RadialGradientPaint(new Point2D.Float(left+width*.52f,top+height*.48f),Math.max(1,width*.58f),new float[]{0,.55f,1},new Color[]{new Color(0x082332),new Color(0x051B2A),new Color(0x031322)}));g.fillRect(left,top,width,height);
        if(!available){g.setColor(new Color(0xA5BBCF));g.drawString("Карта недоступна",left+20,top+40);g.dispose();return;}
        double scale=Math.min((width-6)/1000.0,(height-6)/470.0);g.translate(left+(width-1000*scale)/2,top+(height-470*scale)/2);g.scale(scale,scale);
        g.setColor(new Color(0x143544));g.setStroke(new BasicStroke((float)(.4/scale)));g.draw(grid);
        g.setColor(new Color(0x0C2A39));g.fill(land);
        for(int i=1;i<=5;i++)if(counts[i]>0){g.setColor(new Color(selected==i?0x237B83:0x165363));g.fill(regions[i]);}
        g.setColor(new Color(0x3B7180));g.setStroke(new BasicStroke((float)(.48/scale)));g.draw(borders);
        double[] longitude={0,18,-95,95,16,135},latitude={0,5,22,32,52,-28};
        for(int i=1;i<=5;i++)if(counts[i]>0){float px=(float)x(longitude[i],latitude[i]),py=(float)y(latitude[i]),radius=(float)(52/scale);g.setPaint(new RadialGradientPaint(px,py,radius,new float[]{0,.35f,1},new Color[]{new Color(0x7059F1E4,true),new Color(0x3057E5DC,true),new Color(0x0057E5DC,true)}));g.fill(new Ellipse2D.Float(px-radius,py-radius,radius*2,radius*2));float badge=(float)(14/scale);g.setColor(new Color(0x082834));g.fill(new Ellipse2D.Float(px-badge,py-badge,badge*2,badge*2));g.setColor(new Color(0x58E2EF));g.setStroke(new BasicStroke((float)(1/scale)));g.draw(new Ellipse2D.Float(px-badge,py-badge,badge*2,badge*2));g.setFont(new Font("Segoe UI",Font.BOLD,12).deriveFont((float)(12/scale)));String text=counts[i]>999?"999+":Integer.toString(counts[i]);FontMetrics fm=g.getFontMetrics();g.setColor(Color.WHITE);g.drawString(text,px-fm.stringWidth(text)/2f,py+(fm.getAscent()-fm.getDescent())/2f);}
        g.dispose();
    }
}

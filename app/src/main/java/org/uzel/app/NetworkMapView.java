package org.uzel.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.function.IntConsumer;

/** Offline Natural Earth region overview. Counts are regions, never individual locations.
 * The caller provides accessible filter controls and the reported-geography explanation.
 * View API calls belong on the UI thread; geometry is parsed once per process.
 */
public final class NetworkMapView extends View {
    private static final float MAP_WIDTH=1000f, MAP_HEIGHT=470f;
    private static final int OCEAN=0xff031322, CYAN=0xff58e2ef;
    private static final double[] BADGE_LONGITUDE={0,18,-95,95,16,135,0};
    private static final double[] BADGE_LATITUDE={0,5,22,32,52,-28,0};
    private static Geometry cachedGeometry;
    private final Geometry geometry;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader[] halos=new Shader[7];
    private final int[] counts=new int[7];
    private final String[] labels={"","","","","","",""};
    private final float[] badgeX=new float[7],badgeY=new float[7];
    private final float density,textScale,touchSlop;
    private Shader oceanGlow;
    private float mapScale=1f,mapLeft,mapTop,downX,downY;
    private int selectedRegion=PeerRegions.ALL,pressedRegion=-1;
    private IntConsumer regionListener;

    public NetworkMapView(Context context) {
        super(context);
        density=getResources().getDisplayMetrics().density;
        textScale=getResources().getDisplayMetrics().scaledDensity;
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        setMinimumHeight(Math.round(175*density));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        geometry=loadGeometry(context);
        for(int region=1;region<=5;region++) {
            badgeX[region]=projectX(BADGE_LONGITUDE[region],BADGE_LATITUDE[region]);
            badgeY[region]=projectY(BADGE_LATITUDE[region]);

        }
    }

    /** Missing/negative counts are zero. Unknown is deliberately never placed on the map. */
    public void setData(int[] values,int selected) {
        boolean changed=false;
        for(int region=0;region<counts.length;region++) {
            int count=values!=null&&region<values.length?Math.max(0,values[region]):0;
            if(count!=counts[region]) {
                counts[region]=count;
                labels[region]=count>999?"999+":Integer.toString(count);
                changed=true;
            }
        }
        int next=selected>=0&&selected<counts.length?selected:PeerRegions.ALL;
        if(next!=selectedRegion){selectedRegion=next;changed=true;}
        if(changed)invalidate();
    }

    public void setOnRegionSelectedListener(IntConsumer listener){regionListener=listener;}

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=resolveSize(Math.round(380*density),widthSpec);
        setMeasuredDimension(width,resolveSize(Math.max(getSuggestedMinimumHeight(),
                Math.round(width*.49f)),heightSpec));
    }

    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
        float inset=3*density;
        mapScale=Math.max(.001f,Math.min(Math.max(0,width-2*inset)/MAP_WIDTH,
                Math.max(0,height-2*inset)/MAP_HEIGHT));
        mapLeft=(width-MAP_WIDTH*mapScale)/2;
        mapTop=(height-MAP_HEIGHT*mapScale)/2;
        // Keep the luminous zone physically consistent on home and immersive layouts.
        for(int region=1;region<=5;region++) {
            halos[region]=new RadialGradient(badgeX[region],badgeY[region],68*density/mapScale,
                    new int[]{0x7059f1e4,0x3057e5dc,0x0057e5dc},
                    new float[]{0,.35f,1},Shader.TileMode.CLAMP);
        }
        oceanGlow=new RadialGradient(width*.52f,height*.48f,Math.max(1,width*.58f),
                new int[]{0xff082332,0xff051b2a,OCEAN},
                new float[]{0,.55f,1},Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(oceanGlow);
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        paint.setShader(null);
        canvas.save();
        canvas.translate(mapLeft,mapTop);
        canvas.scale(mapScale,mapScale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.4f*density/mapScale);
        paint.setColor(0xff143544);
        canvas.drawPath(geometry.graticule,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0c2a39);
        canvas.drawPath(geometry.allLand,paint);
        for(int region=1;region<=5;region++) {
            if(counts[region]<=0)continue;
            paint.setColor(selectedRegion==region?0xff237b83:0xff165363);
            canvas.drawPath(geometry.regions[region],paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.48f*density/mapScale);
        paint.setColor(0xff3b7180);
        canvas.drawPath(geometry.boundaries,paint);
        for(int region=1;region<=5;region++) {
            if(counts[region]<=0)continue;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(halos[region]);
            canvas.drawCircle(badgeX[region],badgeY[region],68*density/mapScale,paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(selectedRegion==region?0xff9af9ee:0xff71cfd2);
            paint.setStrokeWidth(.75f*density/mapScale);
            canvas.drawPath(geometry.regions[region],paint);
        }
        for(int region=1;region<=5;region++) {
            if(counts[region]<=0)continue;
            float x=badgeX[region],y=badgeY[region],radius=16*density/mapScale;
            if(selectedRegion==region) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0x8058e2ef);
                paint.setStrokeWidth(density/mapScale);
                canvas.drawCircle(x,y,radius+4*density/mapScale,paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selectedRegion==region?CYAN:0xff082834);
            canvas.drawCircle(x,y,radius,paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(CYAN);
            paint.setStrokeWidth(density/mapScale);
            canvas.drawCircle(x,y,radius,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selectedRegion==region?0xff05202c:0xffe3fcff);
            paint.setTextSize(12*textScale/mapScale);
            paint.setTextAlign(Paint.Align.CENTER);
            // Large accessibility font sizes may need a wider capsule, never smaller type.
            float halfWidth=Math.max(radius,paint.measureText(labels[region])*.5f+6*density/mapScale);
            if(halfWidth>radius) {
                paint.setColor(selectedRegion==region?CYAN:0xff082834);
                canvas.drawRoundRect(x-halfWidth,y-radius,x+halfWidth,y+radius,radius,radius,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setColor(CYAN);
                canvas.drawRoundRect(x-halfWidth,y-radius,x+halfWidth,y+radius,radius,radius,paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(selectedRegion==region?0xff05202c:0xffe3fcff);
            }
            canvas.drawText(labels[region],x,y-(paint.ascent()+paint.descent())*.5f,paint);
        }
        canvas.restore();
    }

    private static float projectX(double longitude,double latitude) {
        return (float)(500+longitude*(460.0/180)*(.86+.14*Math.cos(Math.toRadians(latitude))));
    }
    private static float projectY(double latitude){return (float)(254-latitude*2.6);}

    private static final class Geometry {
        final Path allLand=new Path(),boundaries=new Path(),graticule=new Path();
        final Path[] regions=new Path[7];
        Geometry(){for(int i=0;i<regions.length;i++)regions[i]=new Path();}
    }

    private static synchronized Geometry loadGeometry(Context context) {
        if(cachedGeometry!=null)return cachedGeometry;
        Geometry result=new Geometry();
        // PNM1: big-endian country count, ISO-A2 bytes, ring count, point count,
        // then unsigned 16-bit projected x/y coordinates quantized to 1/16 unit.
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(
                context.getAssets().open("world-countries-110m.bin")))) {
            if(input.readInt()!=0x504e4d31)throw new IOException("Map version");
            int countries=input.readUnsignedShort();
            if(countries>300)throw new IOException("Map country count");
            for(int c=0;c<countries;c++) {
                String code=""+(char)input.readUnsignedByte()+(char)input.readUnsignedByte();
                int region=PeerRegions.group(code);
                int rings=input.readUnsignedShort();
                Path country=new Path();country.setFillType(Path.FillType.EVEN_ODD);
                for(int ring=0;ring<rings;ring++) {
                    int points=input.readUnsignedShort();
                    for(int point=0;point<points;point++) {
                        float x=input.readUnsignedShort()/16f,y=input.readUnsignedShort()/16f;
                        if(point==0)country.moveTo(x,y);else country.lineTo(x,y);
                    }
                    country.close();
                }
                result.allLand.addPath(country);result.boundaries.addPath(country);
                result.regions[region].addPath(country);
            }
            result.allLand.setFillType(Path.FillType.EVEN_ODD);
            for(Path region:result.regions)region.setFillType(Path.FillType.EVEN_ODD);
        }catch(IOException failure) {
            android.util.Log.e("NetworkMapView","Offline map asset unavailable",failure);
        }
        for(int longitude=-180;longitude<=180;longitude+=30) {
            for(int latitude=-60;latitude<=85;latitude+=2) {
                float x=projectX(longitude,latitude),y=projectY(latitude);
                if(latitude==-60)result.graticule.moveTo(x,y);else result.graticule.lineTo(x,y);
            }
        }
        for(int latitude=-60;latitude<=80;latitude+=20) {
            result.graticule.moveTo(projectX(-180,latitude),projectY(latitude));
            result.graticule.lineTo(projectX(180,latitude),projectY(latitude));
        }
        cachedGeometry=result;
        return result;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || regionListener == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedRegion = hitRegion(event.getX(), event.getY());
                downX = event.getX();
                downY = event.getY();
                return pressedRegion != -1;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop) pressedRegion = -1;
                return true;
            case MotionEvent.ACTION_UP:
                int region = pressedRegion;
                pressedRegion = -1;
                if (region != -1 && region == hitRegion(event.getX(), event.getY())) {
                    performClick();
                    regionListener.accept(region);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRegion = -1;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private int hitRegion(float x, float y) {
        float radius = 24 * density;
        int closest = -1;
        float best = radius * radius;
        for (int region = 1; region <= 5; region++) {
            if (counts[region] == 0) continue;
            float dx = x - (mapLeft + badgeX[region] * mapScale);
            float dy = y - (mapTop + badgeY[region] * mapScale);
            float distance = dx * dx + dy * dy;
            if (distance <= best) {
                closest = region;
                best = distance;
            }
        }
        return closest;
    }

}

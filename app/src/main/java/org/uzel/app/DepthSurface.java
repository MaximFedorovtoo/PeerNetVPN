package org.uzel.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Cached layered surface: subtle edge light, recessed lower rim and soft accent glow. */
final class DepthSurface extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body=new RectF(),edge=new RectF();
    private final Path clip=new Path();
    private final float density,radius;
    private final boolean primary;
    private Shader fill,shine,border;
    DepthSurface(float density,float radius,boolean primary){this.density=density;this.radius=radius*density;this.primary=primary;}
    @Override protected void onBoundsChange(Rect b){
        body.set(b.left+density,b.top+density,b.right-density,b.bottom-2*density);
        edge.set(body);edge.inset(density,density);
        fill=new LinearGradient(body.left,body.top,body.right,body.bottom,primary?new int[]{0xff33fff2,0xff00c7e2}:new int[]{0xff18374f,0xff0a2338,0xff031a2c},null,Shader.TileMode.CLAMP);
        shine=new LinearGradient(0,body.top,0,body.bottom,new int[]{primary?0x30ffffff:0x12ffffff,0x00ffffff,0x00000000},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
        border=new LinearGradient(0,body.top,0,body.bottom,new int[]{primary?0xff9bfff8:0xff6b8a9e,primary?0xff23d4db:0xff243d52,primary?0xff008fa8:0xff123347},null,Shader.TileMode.CLAMP);
        clip.reset();clip.addRoundRect(body,radius,radius,Path.Direction.CW);
    }
    @Override public void draw(Canvas canvas){
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(primary?0x5500d7de:0x99000000);
        canvas.save();canvas.translate(0,2*density);canvas.drawRoundRect(body,radius,radius,paint);canvas.restore();
        paint.setAlpha(255);paint.setShader(fill);canvas.drawRoundRect(body,radius,radius,paint);
        paint.setShader(shine);canvas.drawRoundRect(body,radius,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density*.8f);paint.setShader(border);canvas.drawRoundRect(body,radius,radius,paint);
        paint.setShader(null);paint.setColor(primary?0x3200677e:0x38000000);paint.setStrokeWidth(density);
        canvas.save();canvas.clipRect(body.left,body.centerY(),body.right,body.bottom);canvas.drawRoundRect(edge,radius-density,radius-density,paint);canvas.restore();paint.setStyle(Paint.Style.FILL);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}

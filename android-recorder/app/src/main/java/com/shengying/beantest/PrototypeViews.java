package com.shengying.beantest;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Native geometry from the approved prototype; these views never capture or generate audio. */
final class PrototypeViews {
    static final class SpeechArt extends View {
        final Paint p=new Paint(3);final UiKit k;
        SpeechArt(Context c,UiKit k){super(c);this.k=k;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void rect(Canvas c,float x,float y,float w,float h,float r,int color){p.setColor(color);c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/330f,getHeight()/112f);
            c.save();c.rotate(-7,88,48);rect(c,7,12,169,75,25,Color.rgb(255,247,233));p.setColor(Color.rgb(164,56,41));
            int[] bars={12,23,16,36,26,46,20,32,53,35,19,42,27,16,32,22,12};for(int i=0;i<bars.length;i++)c.drawRoundRect(20+i*8,50-bars[i]*.48f,24+i*8,50+bars[i]*.48f,3,3,p);c.restore();
            c.save();c.rotate(7,246,67);rect(c,182,39,139,69,23,Color.rgb(48,54,42));rect(c,201,59,68,7,4,Color.rgb(229,239,219));rect(c,201,75,43,7,4,Color.rgb(229,239,219));p.setColor(Color.rgb(233,207,106));Path star=new Path();star.moveTo(297,68);star.lineTo(301,75);star.lineTo(309,78);star.lineTo(301,81);star.lineTo(297,89);star.lineTo(294,81);star.lineTo(286,78);star.lineTo(294,75);star.close();c.drawPath(star,p);c.restore();c.restore();
        }
    }
    static final class FolderArt extends View {
        final Paint p=new Paint(3);final UiKit k;final String icon;final String count;
        FolderArt(Context c,UiKit k,String icon,int count){super(c);this.k=k;this.icon=icon;this.count=String.format(java.util.Locale.ROOT,"%02d",count);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/145f,getHeight()/86f);
            c.save();c.rotate(8,72,41);p.setColor(0xeaffffff);c.drawRoundRect(24,7,125,64,7,7,p);c.restore();
            c.save();c.rotate(-5,72,48);p.setColor(0xb8ffffff);c.drawRoundRect(12,25,133,77,10,10,p);c.drawRoundRect(12,17,58,36,7,7,p);
            android.graphics.drawable.Drawable d=k.iconDrawable(icon,false,k.ink);if(d!=null){d.setBounds(28,36,58,66);d.draw(c);}p.setColor(k.ink);p.setTextSize(10);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));c.drawText(count,111,61,p);c.restore();c.restore();
        }
    }
    static final class Wave extends View {
        final Paint p=new Paint(3);final int color;
        Wave(Context c,int color){super(c);this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);p.setColor(color);int[] h={5,12,8,17,10,7};float w=getWidth()/13f;for(int i=0;i<h.length;i++){float x=(i*2+1)*w,y=getHeight()/2f,hh=h[i]*getHeight()/22f;c.drawRoundRect(x,y-hh/2,x+w,y+hh/2,w/2,w/2,p);}}
    }
}

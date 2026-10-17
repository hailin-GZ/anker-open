package com.shengying.beantest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** One token vocabulary and one authored line-icon family for all native surfaces. */
final class UiKit {
    final Context c;final int bg,surface,blue,ink,muted,primary,line,warn,coral,mint,butter,lilac,peach,stone;
    UiKit(Context context){c=context;boolean night=false; // Approved white-paper proposal is the app's selected theme.
        bg=Color.parseColor(night?"#171B19":"#FFFFFF");surface=Color.parseColor(night?"#252B27":"#F6F6F1");
        blue=Color.parseColor(night?"#263D50":"#D9EAFA");ink=Color.parseColor(night?"#F1F3EC":"#20221F");
        muted=Color.parseColor(night?"#B9C3B8":"#545D51");primary=Color.parseColor(night?"#BBD9AE":"#334A2C");
        line=Color.parseColor(night?"#3D493F":"#E1E5DA");warn=Color.parseColor(night?"#FFBAA7":"#A63725");
        coral=Color.parseColor(night?"#63372F":"#F46B56");mint=Color.parseColor(night?"#294538":"#D7EFDF");
        butter=Color.parseColor(night?"#4A4327":"#F6E79B");lilac=Color.parseColor(night?"#3E3653":"#E5DFF4");
        peach=Color.parseColor(night?"#503C30":"#F7DFCF");stone=Color.parseColor(night?"#343A32":"#EEEDE7");}
    int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    android.graphics.drawable.Drawable touch(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x22316CB0),shape(color,radius),shape(Color.WHITE,radius));}
    LinearLayout col(){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(c);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String s,int size,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);return t;}
    TextView heading(String s,int size){TextView t=text(s,size,ink);t.setTypeface(null,Typeface.BOLD);return t;}
    TextView copy(String s){TextView t=text(s,14,muted);t.setPadding(0,dp(5),0,dp(5));return t;}
    Button button(String s,boolean filled){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setElevation(0);b.setStateListAnimator(null);b.setTextSize(16);b.setTextColor(filled?Color.WHITE:ink);b.setMinHeight(dp(50));b.setMinimumWidth(0);b.setPadding(dp(16),dp(8),dp(16),dp(8));b.setBackground(touch(filled?ink:stone,17));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);b.setLayoutParams(p);return b;}
    EditText input(String hint){EditText e=new EditText(c);e.setTextSize(17);e.setTextColor(ink);e.setHintTextColor(muted);e.setHint(hint);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setMinHeight(dp(52));GradientDrawable field=shape(Color.WHITE,13);field.setStroke(dp(1),line);e.setBackground(field);return e;}
    LinearLayout card(int color){LinearLayout v=col();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(touch(color,24));return v;}
    android.graphics.drawable.Drawable iconDrawable(String name,boolean filled,int color){int id=c.getResources().getIdentifier("ic_ui_"+name+(filled?"_fill":""),"drawable",c.getPackageName());if(id==0)return null;android.graphics.drawable.Drawable d=c.getDrawable(id).mutate();d.setTint(color);return d;}
    void press(View view){view.setOnTouchListener((v,e)->{if(android.animation.ValueAnimator.areAnimatorsEnabled()){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.animate().scaleX(.972f).scaleY(.972f).setDuration(100).start();else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(.6f)).start();}return false;});}
    View iconButton(String name,String label,Runnable action){FrameLayout f=new FrameLayout(c);f.setContentDescription(label);f.setFocusable(true);f.setClickable(true);f.setBackground(touch(Color.TRANSPARENT,24));Icon i=new Icon(c,name,primary);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);f.addView(i,p);f.setOnClickListener(v->action.run());press(f);f.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return f;}
    void gap(LinearLayout v,int height){View spacer=new View(c);v.addView(spacer,new LinearLayout.LayoutParams(1,dp(height)));}
    void divider(LinearLayout v){View rule=new View(c);rule.setBackgroundColor(line);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMargins(0,dp(14),0,dp(14));v.addView(rule,p);}
    void style(View v){
        if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(ink);e.setHintTextColor(muted);e.setBackgroundTintList(null);GradientDrawable field=shape(Color.WHITE,13);field.setStroke(dp(1),line);e.setBackground(field);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setMinHeight(dp(52));}
        else if(v instanceof Switch){Switch s=(Switch)v;s.setTextColor(ink);s.setTextSize(18);s.setMinHeight(dp(56));s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{primary,muted}));}
        else if(v instanceof Button){Button b=(Button)v;b.setElevation(0);b.setStateListAnimator(null);b.setTextColor(primary);b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(52));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setBackground(touch(blue,12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(7),0,dp(3));if(b.getLayoutParams() instanceof LinearLayout.LayoutParams&&b.getLayoutParams().width>0)p.width=b.getLayoutParams().width;b.setLayoutParams(p);}
        else if(v instanceof TextView)((TextView)v).setTextColor(muted);
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int n=0;n<g.getChildCount();n++)style(g.getChildAt(n));}
    }
    static final class Icon extends View {
        final String name;final Paint p=new Paint(3);int color;boolean filled;
        Icon(Context c,String n,int color){super(c);name=n;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void color(int value){color=value;invalidate();}
        void filled(boolean value){filled=value;invalidate();}
        void path(Canvas c,float...pts){Path q=new Path();q.moveTo(pts[0],pts[1]);for(int i=2;i<pts.length;i+=2)q.lineTo(pts[i],pts[i+1]);c.drawPath(q,p);}
        void box(Canvas c,float a,float b,float d,float e,float r){c.drawRoundRect(a,b,d,e,r,r,p);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int id=getResources().getIdentifier("ic_ui_"+name+(filled?"_fill":""),"drawable",getContext().getPackageName());if(id!=0){android.graphics.drawable.Drawable d=getContext().getDrawable(id).mutate();d.setTint(color);d.setBounds(getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());d.draw(canvas);return;}canvas.save();canvas.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "home":path(canvas,3,11,12,3,21,11);path(canvas,5,10,5,21,10,21,10,15,14,15,14,21,19,21,19,10);break;
                case "archive":path(canvas,3,8,3,5,10,5,12,8,21,8,21,20,3,20,3,8);break;
                case "mark":path(canvas,6,21,6,4,18,4,18,21,12,17,6,21);break;
                case "user":canvas.drawCircle(12,7,4,p);canvas.drawArc(4,13,20,26,180,180,false,p);break;
                case "bell":path(canvas,5,17,7,14,7,9);canvas.drawArc(7,4,17,14,180,180,false,p);path(canvas,17,9,17,14,19,17,5,17);canvas.drawArc(10,17,14,22,0,180,false,p);break;
                case "chat":box(canvas,3,4,21,18,4);path(canvas,6,18,6,22,11,18);path(canvas,7,9,17,9);path(canvas,7,13,14,13);break;
                case "danger":path(canvas,12,3,22,21,2,21,12,3);path(canvas,12,9,12,14);canvas.drawPoint(12,18,p);break;
                case "search":canvas.drawCircle(10,10,6,p);path(canvas,15,15,21,21);break;
                case "filter":path(canvas,3,5,21,5,14,13,14,20,10,18,10,13,3,5);break;
                case "grid":for(int x=3;x<18;x+=11)for(int y=3;y<18;y+=11)box(canvas,x,y,x+7,y+7,1);break;
                case "list":for(int y=5;y<21;y+=7){canvas.drawPoint(4,y,p);path(canvas,9,y,21,y);}break;
                case "back":path(canvas,14,5,7,12,14,19);break;
                case "next":path(canvas,9,6,15,12,9,18);break;
                case "plus":path(canvas,12,5,12,19);path(canvas,5,12,19,12);break;
                case "clock":canvas.drawCircle(12,12,9,p);path(canvas,12,6,12,12,16,14);break;
                case "heart":Path heart=new Path();heart.moveTo(12,21);heart.cubicTo(-7,9,6,-3,12,6);heart.cubicTo(18,-3,31,9,12,21);canvas.drawPath(heart,p);break;
                case "bean":box(canvas,7,2,17,22,5);path(canvas,10,8,10,15);path(canvas,14,8,14,15);break;
                default:for(int x=3;x<24;x+=4){float len=(x==11?18:x==7||x==15?12:6);path(canvas,x,12-len/2,x,12+len/2);}
            }canvas.restore();
        }
    }
}

package com.shengying.beantest;

import com.github.promeg.pinyinhelper.Pinyin;
import java.text.Normalizer;

/** Local dictionary lookup, not an AI model. Match whole Mandarin syllables. */
final class NameSounds {
    static String[] ofName(String name){
        if(name.length()<2)return null; // A single syllable would match too much ordinary speech.
        String[] sounds=new String[name.length()];
        for(int i=0;i<name.length();i++){
            char c=name.charAt(i);
            if(!Pinyin.isChinese(c))return null;
            sounds[i]=Pinyin.toPinyin(c);
        }
        return sounds;
    }
    static boolean contains(String text,String[] name){
        if(name==null||text==null)return false;
        // Retain punctuation/digits as barriers. Never join XI+AN into XIAN.
        String raw=Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("[\\p{Zs}\\t]+","");
        for(int start=0;start+name.length<=raw.length();start++){
            boolean match=true;
            for(int j=0;j<name.length;j++){
                char c=raw.charAt(start+j);
                if(!Pinyin.isChinese(c)||!name[j].equals(Pinyin.toPinyin(c))){match=false;break;}
            }
            if(match)return true;
        }
        return false;
    }
}

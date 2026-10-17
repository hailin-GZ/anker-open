package com.shengying.beantest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keyword monitoring, independent of Android, ASR and model providers. */
final class KeywordMonitor {
    static final long COOLDOWN_MS=4000;
    private static final Pattern NUMBER=Pattern.compile("([0-9零〇一二两三四五六七八九十百千万]+)\\s*[号號]");
    static final class Rule {
        final String label,value,normalized;
        final String[] sounds;
        Rule(String label,String value){this(label,value,false);}
        Rule(String label,String value,boolean homophones){this.label=label;this.value=value.trim();normalized=normalize(this.value);sounds=homophones?NameSounds.ofName(normalized):null;}
    }
    private List<Rule> rules=Collections.emptyList();
    private final LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();
    private final Map<String,Long> lastAlert=new LinkedHashMap<>();
    private boolean enabled,foreground=true;

    void setRules(List<Rule> incoming){
        LinkedHashMap<String,Rule> unique=new LinkedHashMap<>();
        for(Rule r:incoming)if(!r.normalized.isEmpty())unique.putIfAbsent(r.normalized,r);
        rules=new ArrayList<>(unique.values());
        lastAlert.keySet().retainAll(unique.keySet());
    }
    int ruleCount(){return rules.size();}
    void setEnabled(boolean value){enabled=value;}
    void setForeground(boolean value){foreground=value;}

    List<Rule> accept(String source,String session,String utterance,String text,boolean isFinal,long now){
        if(!"bean_live".equals(source)||!isFinal||session==null||session.isEmpty()||utterance==null||utterance.isEmpty())return Collections.emptyList();
        String id=session+'\u0000'+utterance;
        if(seen.containsKey(id))return Collections.emptyList();
        seen.put(id,true);
        while(seen.size()>256)seen.remove(seen.keySet().iterator().next());
        if(!enabled||!foreground)return Collections.emptyList();
        List<Rule> matches=find(text);
        List<Rule> allowed=new ArrayList<>();
        for(Rule r:matches){Long previous=lastAlert.get(r.normalized);
            if(previous==null||now-previous>=COOLDOWN_MS){allowed.add(r);lastAlert.put(r.normalized,now);}}
        return allowed;
    }

    /** Explicit, visibly labelled text test; does not masquerade as an ASR event. */
    List<Rule> test(String text){return enabled&&foreground?find(text):Collections.emptyList();}

    private List<Rule> find(String text){
        List<Rule> result=new ArrayList<>();
        for(Rule r:rules)if(!matchKind(r,text).isEmpty())result.add(r);
        return result;
    }

    static String matchKind(Rule r,String text){
        String normalized=normalize(text);int from=0,index;
            while((index=normalized.indexOf(r.normalized,from))>=0){
                int end=index+r.normalized.length();
                boolean left=index==0||!wordConflict(r.normalized.codePointAt(0),normalized.codePointBefore(index));
                boolean right=end==normalized.length()||!wordConflict(r.normalized.codePointBefore(r.normalized.length()),normalized.codePointAt(end));
                if(left&&right)return "literal";from=index+1;
            }
        return NameSounds.contains(text,r.sounds)?"homophone":"";
    }

    private static boolean wordConflict(int keywordEdge,int neighbor){
        if(Character.isDigit(keywordEdge))return Character.isDigit(neighbor);
        if(Character.UnicodeScript.of(keywordEdge)==Character.UnicodeScript.LATIN)
            return Character.UnicodeScript.of(neighbor)==Character.UnicodeScript.LATIN||Character.isDigit(neighbor);
        return false;
    }

    static String normalize(String input){
        if(input==null)return "";
        String value=Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher=NUMBER.matcher(value);StringBuffer out=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(number(matcher.group(1))+"号"));
        matcher.appendTail(out);
        return out.toString().replaceAll("[^\\p{L}\\p{N}]+"," ").trim()
            .replaceAll("(?<=[\\p{IsHan}0-9])\\s+(?=[\\p{IsHan}0-9])","");
    }

    private static String number(String s){
        final String digits="零一二三四五六七八九";boolean units=s.matches(".*[十百千万].*");
        if(!units){StringBuilder out=new StringBuilder();for(char c:s.toCharArray()){
            int digit=digits.indexOf(c);out.append(c=='〇'?"0":c=='两'?"2":digit>=0?String.valueOf(digit):String.valueOf(c));}return out.toString();}
        int total=0,section=0,current=0;
        for(char c:s.toCharArray()){
            if(c=='万'){section+=current;total+=(section==0?1:section)*10000;section=0;current=0;continue;}
            int unit=c=='十'?10:c=='百'?100:c=='千'?1000:0;
            if(unit>0){section+=(current==0?1:current)*unit;current=0;}
            else {int digit=c=='两'?2:c=='〇'?0:digits.indexOf(c);if(digit<0&&c>='0'&&c<='9')digit=c-'0';current=Math.max(0,digit);}
        }return String.valueOf(total+section+current);
    }
}

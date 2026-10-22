package family.libretv.tv;

/** One bounded recovery budget per channel; manual actions explicitly begin a new round. */
final class LiveRecoveryPolicy {
    enum Kind { EDGE, RELOAD, SWITCH, STOP }
    static final class Action {
        final Kind kind;
        final int line;
        final long delayMs;
        Action(Kind kind,int line,long delayMs){this.kind=kind;this.line=line;this.delayMs=delayMs;}
    }
    private static final int MAX_RECOVERIES=5;
    private boolean[] visited=new boolean[0];
    private int current,retries,remaining;
    void begin(int lines,int first){
        if(lines<1)throw new IllegalArgumentException("No live lines");
        visited=new boolean[lines];current=Math.floorMod(first,lines);stable();
    }
    int current(){return current;}
    void stable(){java.util.Arrays.fill(visited,false);visited[current]=true;remaining=MAX_RECOVERIES;retries=0;}
    Action fail(boolean windowExpired,boolean nonRetryable){
        if(remaining==0)return new Action(Kind.STOP,current,0);
        if(!nonRetryable&&retries<2){
            // Give brief upstream outages time to recover without an endless retry loop.
            remaining--;long delay=windowExpired?(retries==0?0:1000):(retries==0?2000:6000);retries++;
            return new Action(windowExpired?Kind.EDGE:Kind.RELOAD,current,delay);
        }
        for(int n=1;n<visited.length;n++){
            int next=(current+n)%visited.length;
            if(!visited[next]){remaining--;current=next;visited[current]=true;retries=0;return new Action(Kind.SWITCH,current,500);}
        }
        return new Action(Kind.STOP,current,0);
    }
}

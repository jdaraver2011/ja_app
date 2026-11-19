package com.example.ja;
public final class JaLiveTvRecoveryTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        int[] failures={1,0,0};boolean[] ended={false,false,false};
        check(JaLiveTvRecovery.next(0,failures,ended)==0);
        failures[0]=2;check(JaLiveTvRecovery.next(0,failures,ended)==1);
        ended[0]=true;failures[0]=0;check(JaLiveTvRecovery.next(0,failures,ended)==1);
        ended[1]=true;check(JaLiveTvRecovery.next(0,failures,ended)==2);
        ended[2]=true;check(JaLiveTvRecovery.next(0,failures,ended)==-1);
        check(JaLiveTvRecovery.next(0,new int[]{2},new boolean[]{false})==-1);
        check(JaLiveTvRecovery.backoff(1)==1000&&JaLiveTvRecovery.backoff(2)==2000&&JaLiveTvRecovery.backoff(99)==8000);
        check(JaLiveTvRecovery.stalled(true,true,false,false,1000,31000));
        check(!JaLiveTvRecovery.stalled(false,true,false,false,1000,31000));
        check(!JaLiveTvRecovery.stalled(true,false,false,false,1000,31000));
        check(!JaLiveTvRecovery.stalled(true,true,true,false,1000,31000));
        check(!JaLiveTvRecovery.stalled(true,true,false,true,1000,31000));
        System.out.println("Live TV recovery passed");
    }
}

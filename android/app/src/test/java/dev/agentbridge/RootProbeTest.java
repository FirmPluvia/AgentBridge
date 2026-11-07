package dev.agentbridge;

import com.topjohnwu.superuser.Shell;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** libsu Job contract regression, not an Android/root-manager hardware test. */
public class RootProbeTest {
 static class Job extends Shell.Job {
  List<String> stdout,stderr;
  final String emitted;
  final int code;
  Job(String emitted,int code){this.emitted=emitted;this.code=code;}
  @Override public Shell.Job to(List<String> out){stdout=out;return this;}
  @Override public Shell.Job to(List<String> out,List<String> err){stdout=out;stderr=err;return this;}
  @Override public Shell.Job add(String...commands){assertArrayEquals(new String[]{"/system/bin/id -u"},commands);return this;}
  @Override public Shell.Job add(InputStream in){throw new UnsupportedOperationException();}
  @Override public Shell.Result exec(){
   if(stdout!=null && !emitted.isEmpty())stdout.add(emitted);
   if(stderr!=null && code!=0)stderr.add("denied");
   return new Shell.Result(){public List<String> getOut(){return stdout==null?Collections.emptyList():stdout;}public List<String>getErr(){return stderr==null?Collections.emptyList():stderr;}public int getCode(){return code;}};
  }
  @Override public void submit(Executor executor,Shell.ResultCallback cb){throw new UnsupportedOperationException();}
  @Override public Future<Shell.Result> enqueue(){throw new UnsupportedOperationException();}
 }
 @Test public void rootUidIsCapturedExplicitly() throws BridgeError {Job job=new Job("0",0);assertEquals(0,RootProbe.INSTANCE.verify(job,true));assertNotNull(job.stdout);assertNotNull(job.stderr);}
 @Test public void nonRootUidNeverPasses(){try{RootProbe.INSTANCE.verify(new Job("2000",0),false);fail();}catch(BridgeError e){assertEquals("PERMISSION_DENIED",e.getCode());}}
 @Test public void absentUidIsFailureInsteadOfPretendMinusOneUid(){try{RootProbe.INSTANCE.verify(new Job("",0),true);fail();}catch(BridgeError e){assertEquals("BACKEND_UNAVAILABLE",e.getCode());}}
 @Test public void failedCommandCannotMasqueradeAsUidZero(){try{RootProbe.INSTANCE.verify(new Job("0",1),true);fail();}catch(BridgeError e){assertEquals("BACKEND_UNAVAILABLE",e.getCode());}}
}

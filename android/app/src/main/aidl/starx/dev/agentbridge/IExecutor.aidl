package starx.dev.agentbridge;
import android.os.ParcelFileDescriptor;
interface IExecutor {
 ParcelFileDescriptor execute(String token, in String[] argv, int timeoutMs, int limit);
 oneway void cancel(String token);
 int uid();
 void destroy();
}

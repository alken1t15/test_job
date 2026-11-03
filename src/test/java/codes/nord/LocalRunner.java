package codes.nord;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;
import java.nio.file.*;
public class LocalRunner {
 public static void main(String[] args)throws Exception {
  var listener=new SummaryGeneratingListener();
  var launcher=LauncherFactory.create(); launcher.registerTestExecutionListeners(listener);
  launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(EndpointTest.class)).build());
  var summary=listener.getSummary(); summary.printTo(new java.io.PrintWriter(System.out,true));
  summary.printFailuresTo(new java.io.PrintWriter(System.out,true));
  StringBuilder html=new StringBuilder("<!doctype html><meta charset='UTF-8'><title>Проверка сервиса NordCodes</title><style>body{font:17px system-ui;max-width:1000px;margin:40px auto}pre{white-space:pre-wrap;background:#f3f3f3;padding:16px}</style><h1>Результаты проверки сервиса</h1>");
  html.append("<p>Проверок: ").append(summary.getTestsFoundCount()).append(". Успешно: ").append(summary.getTestsSucceededCount()).append(". Ошибок: ").append(summary.getTestsFailedCount()).append(".</p>");
  for(var f:summary.getFailures()) html.append("<h2>").append(escape(f.getTestIdentifier().getDisplayName())).append("</h2><pre>").append(escape(f.getException().toString())).append("</pre>");
  Files.writeString(Path.of("target/report.html"),html);
  System.exit(summary.getTotalFailureCount()==0?0:1);
 }
 static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}

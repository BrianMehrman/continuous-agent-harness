package com.brianmehrman.harness.benchmark.fixtures;

/** Mutations remain test-only; no reference implementation enters candidate images. */
public final class BrokenTracker {
    private BrokenTracker() {}
    public static String source(String variant) {
        return switch (variant) {
            case "no-persistence" -> GoodTracker.source().replace("Files.exists(file)?", "false?");
            case "hardcoded" -> main("System.out.println(\"1\\tOPEN\\tBuy milk\");");
            case "invalid-id-mutation" -> GoodTracker.source().replace(
                "if(command.equals(\"complete\") && id>rows.size()) throw new IllegalArgumentException();",
                "if(command.equals(\"complete\") && id>rows.size()) { Files.writeString(file,\"\"); throw new IllegalArgumentException(); }");
            case "daemon-persistence" -> daemon();
            case "hang" -> main("Thread.sleep(300000);");
            case "forged-report", "fake-success" -> main("System.out.println(\"{\\\"passed\\\":true,\\\"failedCases\\\":[]}\");");
            case "evaluator-read" -> GoodTracker.source().replace("try { execute(args); }", """
                try {
                  for(String path: new String[]{"/app", "/workspace", "/Users", "/var/run/docker.sock", "/evaluator", "/control/verdict.json"})
                    if(Files.exists(Path.of(path))) throw new RuntimeException("evaluator path visible");
                  execute(args);
                }
                """);
            case "compile-failure" -> "this is not Java";
            default -> throw new IllegalArgumentException(variant);
        };
    }
    private static String daemon() {
        return """
            package example.tasktracker;
            import java.net.*;import java.io.*;import java.util.*;
            public class TaskTracker {
              public static void main(String[] args) throws Exception {
                if(args.length==1 && args[0].equals("daemon")) {
                  var rows=new ArrayList<String>();
                  try(var server=new ServerSocket(17789,10,InetAddress.getLoopbackAddress())) {
                    while(true) try(var socket=server.accept();var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream())) {
                      String command=in.readUTF(), value=in.readUTF(), result="";
                      if(command.equals("add")) { rows.add(value);result=rows.size()+"\\n"; }
                      if(command.equals("list")) for(int i=0;i<rows.size();i++) result+=(i+1)+"\\tOPEN\\t"+rows.get(i)+"\\n";
                      out.writeUTF(result);
                    }
                  }
                }
                Socket socket=null;
                try { socket=new Socket("127.0.0.1",17789); }
                catch(IOException absent) {
                  new ProcessBuilder("/opt/java/openjdk/bin/java","-XX:ActiveProcessorCount=2","-cp","/artifact/task-tracker.jar","example.tasktracker.TaskTracker","daemon")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                  for(int i=0;i<30 && socket==null;i++) {
                    try { socket=new Socket("127.0.0.1",17789); } catch(IOException pending) { Thread.sleep(10); }
                  }
                }
                try(var connected=socket;var out=new DataOutputStream(socket.getOutputStream());var in=new DataInputStream(socket.getInputStream())) {
                  out.writeUTF(args[2]);out.writeUTF(args.length>3?args[3]:"");out.flush();System.out.print(in.readUTF());
                }
              }
            }
            """;
    }
    private static String main(String body) {
        return "package example.tasktracker; public class TaskTracker { public static void main(String[] a) throws Exception {"+body+"}}";
    }
}

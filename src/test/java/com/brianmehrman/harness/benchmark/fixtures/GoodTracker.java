package com.brianmehrman.harness.benchmark.fixtures;

/** Private reference source, never included in starter or image. */
public final class GoodTracker {
    private GoodTracker() {}
    public static String source() {
        return """
            package example.tasktracker;
            import java.nio.file.*;
            import java.nio.charset.StandardCharsets;
            import java.util.*;
            public class TaskTracker {
              public static void main(String[] args) {
                try { execute(args); }
                catch (IllegalArgumentException e) { System.err.println("invalid input"); System.exit(2); }
                catch (Exception e) { System.err.println("storage failed"); System.exit(1); }
              }
              static void execute(String[] a) throws Exception {
                if(a.length<3 || !a[0].equals("--data-dir") || a[1].isBlank()) throw new IllegalArgumentException();
                Path dir;
                try { dir=Path.of(a[1]); } catch (InvalidPathException e) { throw new IllegalArgumentException(); }
                String command=a[2];
                if(!Set.of("add","list","complete").contains(command)) throw new IllegalArgumentException();
                if(a.length!=(command.equals("list")?3:4)) throw new IllegalArgumentException();
                String description=command.equals("add")?a[3].trim():"";
                if(command.equals("add") && (description.isBlank() || a[3].chars().anyMatch(c->c==0 || c==9 || c==10 || c==13))) throw new IllegalArgumentException();
                int id=0;
                if(command.equals("complete")) {
                  if(!a[3].matches("[0-9]+")) throw new IllegalArgumentException();
                  try { id=Integer.parseInt(a[3]); } catch(NumberFormatException e) { throw new IllegalArgumentException(); }
                  if(id<=0) throw new IllegalArgumentException();
                }
                Path file=dir.resolve("tasks.txt");
                List<String> rows=Files.exists(file)?new ArrayList<>(Files.readAllLines(file,StandardCharsets.UTF_8)):new ArrayList<>();
                if(command.equals("list")) { for(String row:rows) System.out.println(row); return; }
                if(command.equals("complete") && id>rows.size()) throw new IllegalArgumentException();
                if(command.equals("add")) { id=rows.size()+1; rows.add(id+"\\tOPEN\\t"+description); }
                else { String[] old=rows.get(id-1).split("\\t",3); rows.set(id-1,id+"\\tDONE\\t"+old[2]); }
                Files.createDirectories(dir);
                Path temp=dir.resolve("next.txt");
                Files.write(temp,rows,StandardCharsets.UTF_8);
                Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                System.out.println(command.equals("add")?Integer.toString(id):id+"\\tDONE");
              }
            }
            """;
    }
}

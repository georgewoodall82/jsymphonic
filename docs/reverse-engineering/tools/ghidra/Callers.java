// Ghidra headless script: print the callers (recursively, up to a depth) of the functions containing some addresses,
// to walk from a string or a helper up to a named function.
//
//   analyzeHeadless PROJECT_DIR NAME -process AuraPACAPI.dll -noanalysis -readOnly \
//       -scriptPath tools/ghidra -postScript Callers.java /path/out.txt 5 6d299d0b 6d2ba8c0
//
// Arguments: output file, maximum depth, then one or more addresses (hexadecimal, without 0x).
// "[data ref ...]" lines are references from data (virtual tables, function pointers) that are not calls.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.*;
import java.io.*;
import java.util.*;

public class Callers extends GhidraScript {
  PrintWriter pw;

  void up(Function f, int depth, int max, Set<Function> seen) {
    for (int i = 0; i < depth; i++) pw.print("  ");
    pw.println(f.getName() + " @ " + f.getEntryPoint());
    if (depth >= max || !seen.add(f)) return;
    Set<Function> callers = new TreeSet<>((a, b) -> a.getEntryPoint().compareTo(b.getEntryPoint()));
    for (Reference r : getReferencesTo(f.getEntryPoint())) {
      Function c = getFunctionContaining(r.getFromAddress());
      if (c != null) callers.add(c);
      else { for (int i = 0; i <= depth; i++) pw.print("  "); pw.println("[data ref " + r.getFromAddress() + " " + r.getReferenceType() + "]"); }
    }
    for (Function c : callers) up(c, depth + 1, max, seen);
  }

  public void run() throws Exception {
    String[] a = getScriptArgs();
    pw = new PrintWriter(new FileWriter(a[0]));
    int max = Integer.parseInt(a[1]);
    for (int i = 2; i < a.length; i++) {
      Address ad = toAddr(a[i]);
      Function f = getFunctionContaining(ad);
      if (f == null) {
        pw.println("no function at " + a[i]);
        for (Reference r : getReferencesTo(ad)) {
          Function c = getFunctionContaining(r.getFromAddress());
          pw.println(" referenced from " + r.getFromAddress() + " " + (c == null ? "?" : c.getName()));
        }
        continue;
      }
      up(f, 0, max, new HashSet<>());
      pw.println();
    }
    pw.close();
  }
}

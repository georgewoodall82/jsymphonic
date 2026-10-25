// Ghidra headless script: decompile every function of the program to one text file.
//
//   analyzeHeadless PROJECT_DIR NAME -import AuraPACAPI.dll \
//       -scriptPath tools/ghidra -postScript DumpAll.java /path/to/aura.c
//
// The output has one "//==== name @ address" line per function followed by its C pseudocode, so it can be searched
// with grep, and post-processed (for example to name the functions after the "Class::method" strings they contain).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import java.io.*;

public class DumpAll extends GhidraScript {
  public void run() throws Exception {
    String out = getScriptArgs()[0];
    PrintWriter pw = new PrintWriter(new FileWriter(out));
    DecompInterface d = new DecompInterface();
    d.openProgram(currentProgram);
    FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
    while (it.hasNext() && !monitor.isCancelled()) {
      Function f = it.next();
      DecompileResults r = d.decompileFunction(f, 60, monitor);
      pw.println("//==== " + f.getName() + " @ " + f.getEntryPoint());
      if (r != null && r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC()); else pw.println("// failed");
    }
    pw.close();
  }
}

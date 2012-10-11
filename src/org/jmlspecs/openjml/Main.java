/*
 * This file is part of the OpenJML project. 
 * Author: David R. Cok
 */

package org.jmlspecs.openjml;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import org.jmlspecs.annotation.NonNull;
import org.jmlspecs.annotation.Nullable;
import org.jmlspecs.annotation.Pure;

import com.sun.tools.javac.comp.JmlAttr;
import com.sun.tools.javac.comp.JmlCheck;
import com.sun.tools.javac.comp.JmlEnter;
import com.sun.tools.javac.comp.JmlFlow;
import com.sun.tools.javac.comp.JmlMemberEnter;
import com.sun.tools.javac.comp.JmlResolve;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.CommandLine;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.parser.JmlFactory;
import com.sun.tools.javac.parser.JmlScanner;
import com.sun.tools.javac.parser.JmlTreeInfo;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JavacMessages;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

/**
 * This class is the main entry point for the JML tool. It uses the javac
 * compiler, but overrides some of the functionality here in order to register
 * tools for the compiler to use that are extensions with JML functionality of
 * the usual tools. Also the handling of JML-specific options is initialized
 * here.  For programmatic access to the facilities of openjml, use the methods
 * of the API class.
 * 
 */

/* NOTE ON OUTPUT:
 * Output is produced by this program in a variety of ways, through mechanisms
 * that enable it to be captured for various means of display by the wrapping
 * UI.
 * 
 * Log - there is one Log instance per context, retrieved by Log.instance(context).
 * It has a number of writers:
 *  log.noticeWriter - general information, often guarded by if (verbose) or if (jmldebug)
 *  log.warnWriter - for compiler/JML/ESC warnings
 *  log.errWriter - for compiler/JML/ESC errors
 *  
 * The IProgressReporter - this is used for messages that report progress back
 * to the UI (textual or graphic).
 * 
 * Diagnostic listener - this listens for warnings and errors, as written to
 * log.warnWriter and log.errWriter.
 * 
 * The output of the log goes to the registered instance of Log [ by 
 * context.put(logKey, new Log(...)) ].  If no log is registered, a default log
 * is used, which sends all channels of information to the PrintWriter registered
 * under context.put(outKey,...).  There is a constructor for a Log that allows
 * the notice, warning and error output to be directed to different PrintWriters.
 * 
 * If there is no DiagnosticListener registered, the warning and error outputs
 * are treated as described above.  If there is a DiagnosticListener, then the
 * diagnostic output is sent to that listener instead.
 * 
 * For OpenJML:
 * - command-line tool: no diagnostic listener is defined; Log output can be
 * directed to either System.out or System.err by a command-line option
 * - Eclipse: a diagnostic listener is defined that converts warnings and errors
 * into Eclipse markers; Log output is captured by a PrintWriter that directs
 * the output to the Eclipse console.
 * 
 * Progress Reporting: Various tools within OpenJML report progress (e.g. as
 * individual files are parsed).  The progress messages are tiered: 
 *  level 1: low volume, appropriate for text and normal output
 *  level 2: transient progress messages
 *  level 3: verbose only
 * A tool can receive these progress reports by
 * registering as a delegate via
 * 
 * TODO - check and complete the documentation above
 */
public class Main extends com.sun.tools.javac.main.Main {

    /** The compilation unit context associated with this instance of Main
     * (for the programmatic API); for the command-line API it is simply 
     * the most recent value of the context, and is used that way in testing. */
    protected Context context;
    
    /** This is a cached copy of the output writer, for use when
     * direct writing is needed (such as printing out help information) before
     * the Log facility is registered. This value is also present in the
     * superclass, but it is private there.
     */
    @NonNull
    protected PrintWriter out;
    
    /** The diagListener provided when an instance of Main is constructed.
     * The listener will be notified when any diagnostic is generated.
     */
    @Nullable
    protected DiagnosticListener<?> diagListener;
    
    /** Instances of this class are used to abruptly terminate long-running
     * JML operations.
     * @author David R. Cok
     */
    public static class JmlCanceledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JmlCanceledException(String message) {
            super(message);
        }
    }
    
    // TODO - review use of and document these progress reporters; perhaps move them
    
    public ChangeableProgressReporter progressDelegate = new ChangeableProgressReporter();

    public static interface IProgressReporter {
        void setContext(Context context);
        boolean report(int ticks, int level, String message);
    }
    
    static public class ChangeableProgressReporter implements IProgressReporter {
        private IProgressReporter delegate;
        public boolean report(int ticks, int level, String message) {
            if (delegate != null) return delegate.report(ticks,level,message);
            return false;
        }
        @Pure
        public boolean hasDelegate() { return delegate != null; }
        public void setDelegate(IProgressReporter d) {
            delegate = d;
        }
        @Override
        public void setContext(Context context) { if (delegate!= null) delegate.setContext(context); }
    }
    
    public static class PrintProgressReporter implements IProgressReporter {
        PrintWriter pw;
        Context context;
        
        public PrintProgressReporter(Context context, OutputStream out) {
            pw = new PrintWriter(out);
            this.context = context;
        }
        
        public PrintProgressReporter(Context context, PrintWriter w) {
            pw = w;
            this.context = context;
        }
        
        @Override
        public boolean report(int ticks, int level, String message) {
            if (level <= 1 || (context != null && JmlOption.isOption(context,JmlOption.JMLVERBOSE))) pw.println(message);
            return false;
        }

        @Override
        public void setContext(Context context) { this.context = context; }
    }

    
    
    /**
     * Construct a compiler instance; all options are set to default values.  
     * Errors go to stderr.
     */
    // @edu.umd.cs.findbugs.annotations.SuppressWarnings("NM_SAME_SIMPLE_NAME_AS_SUPERCLASS")
    public Main() throws java.io.IOException {
        this(Strings.applicationName, new PrintWriter(System.err, true), null);
    }

    /**
     * Construct an initialized compiler instance; all options are set to default values.
     * @param applicationName the name to use for the application
     * @param out  the PrintWriter to which to send information and error messages
     * @param diagListener the listener to receive problem and warning reports
     * @param args command-line options
     */
    public Main(/*@ non_null */String applicationName, 
                /*@ non_null */PrintWriter out, 
                @Nullable DiagnosticListener<? extends JavaFileObject> diagListener,
                String... args) 
        throws java.io.IOException {
        super(applicationName,out);
        check(); // Aborts if the environment does not support OpenJML
        this.out = out;
        this.diagListener = diagListener;
        initialize(args); // Creates a new context and initializes it per the command-line arguments
    }
    
    /** Checks that the tool is being run with an adequate JVM - and exits abruptly if not */
    public void check() {
        javax.lang.model.element.ElementKind[] kinds = javax.lang.model.element.ElementKind.values();
        if (kinds[kinds.length-1] == javax.lang.model.element.ElementKind.OTHER) {
            System.out.println("OpenJML is being run with a Java 6 VM. Use this command-line:");
            System.out.println("  java -Xbootclasspath/p:openjml.jar -jar openjml.jar");
            System.exit(99);
        }
    }

    /** The external entry point - simply calls compiler(args) and exits with the
     * exit code returned.
     * @param args the command-line arguments
     */
    //@ requires args != null && \nonnullelements(args);
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("-Xjdb")) {
            // TODO: Copied directly from com.sun.tools.javac.Main and not tested
            String[] newargs = new String[args.length + 2];
            Class<?> c = Class.forName("com.sun.tools.example.debug.tty.TTY");
            Method method = c.getDeclaredMethod ("main", new Class[] {args.getClass()});
            method.setAccessible(true);
            System.arraycopy(args, 1, newargs, 3, args.length - 1);
            newargs[0] = "-connect";
            newargs[1] = "com.sun.jdi.CommandLineLaunch:options=-esa -ea:com.sun.tools...";
            newargs[2] = "org.jmlspecs.openjml.Main";
            method.invoke(null, new Object[] { newargs });
        } else {
            System.exit(execute(args));
        }
    }
    
    // TODO: Move these? Get them from Option?
    
    /** The option string for requesting help information */
    @NonNull
    final public static String helpOption = "-help";
    
    /** The option string for requesting interactive mode */
    @NonNull
    final public static String interactiveOption = "-i";

    /** The option string for running jmldoc */
    @NonNull
    final public static String jmldocOption = "-doc";

    /** Invokes the compiler on the given command-line arguments; errors go to
     * stdout.
     * @param args the command-line arguments
     * @return     the exit code, as returned to the shell - 0 is success
     */
    //@ requires args != null && \nonnullelements(args);
    public static int execute(String[] args) {
        if (args != null) {
            for (String a: args) {
                if (jmldocOption.equals(a)) {
                    return org.jmlspecs.openjml.jmldoc.Main.execute(args);
                }
            }
        }
        return execute(args,false);  // The boolean: true - errors to stdErr, false - errors to stdOut
    }

    /** A programmatic interface to the compiler that returns the exit code, but
     * does not itself call System.exit.  [This is called execute rather than
     * compile as in com.sun.tools.javac.Main because we also have to override
     * com.sun.tools.javac.main.Main.compile ]
     * @param args the command-line arguments
     * @param useStdErr if true, errors go to stderr; if false they go to stdout
     * @return the exit code as sent to the shell (0 is success)
     */ 
    //@ requires args != null && \nonnullelements(args);
    public static int execute(String[] args, boolean useStdErr) {
        return execute(new PrintWriter(useStdErr ? System.err : System.out, true), null, args);
    }
    
    /** Static method to do the work of Main.
     * 
     * @param writer where to write output that is not sent to the diagnosticListener
     * @param diagListener a listener to hear any compiler diagnostics produced
     * @param args the command-line arguments
     * @return the exit code
     */
    public static int execute(@NonNull PrintWriter writer, @Nullable DiagnosticListener<JavaFileObject> diagListener, @NonNull String[] args) {
        int errorcode = com.sun.tools.javac.main.Main.EXIT_ERROR; // 1
        try {
            if (args == null) {
                Context context = new Context(); // This is a temporary context just for this error message.
                                    // It is not the one used for the options and compilation
                Log log = Log.instance(context);
                JavacMessages.instance(context).add(Strings.messagesJML);
                log.error("jml.main.null.args","org.jmlspecs.openjml.Main.main");
                errorcode = com.sun.tools.javac.main.Main.EXIT_CMDERR; // 2
            } else {
                // We have to interpret the -java option before we start
                // the compiler (which does the normal option processing).
                // Since this is rare, we'll require that it be the first
                // option.
                boolean useJavaCompiler = args.length > 0 &&
                        args[0].equals(JmlOption.USEJAVACOMPILER.optionName());
                if (useJavaCompiler) {
                    String[] newargs = new String[args.length-1];
                    System.arraycopy(args,1,newargs,0,newargs.length);
                    errorcode = com.sun.tools.javac.Main.compile(newargs);
                } else if (args.length > 0 && args[0].equals(interactiveOption)) {
                    // interactive mode // TODO - what is this? do we still support it?
                    errorcode = new org.jmlspecs.openjml.Interactive().run(args);
                    if (errorcode != 0) writer.println("ENDING with exit code " + errorcode); 
                } else {
                    Main compiler = new Main(Strings.applicationName, writer, diagListener, (String[])null);
                    errorcode = compiler.compile(args);
                    if (errorcode != 0 && Options.instance(compiler.context).get(JmlOption.JMLTESTING.optionName()) != null) writer.println("ENDING with exit code " + errorcode); // TODO - not sure we want this - but we'll need to change the tests
                }
            }
        } catch (Exception e) {
            // Most exceptions are caught prior to this, so this will happen only for the
            // most catastrophic kinds of failure such as failures to initialize
            // properly.  (You can test this by programmatically throwing an exception in the try
            // block above.)
            Context context = new Context(); // This is a temporary context just for this error message.
                                               // It is not the one used for the options and compilation
            Log log = Log.instance(context);
            JavacMessages.instance(context).add(Strings.messagesJML);
            log.error("jml.toplevel.exception",e);
            e.printStackTrace(System.err);
            errorcode = com.sun.tools.javac.main.Main.EXIT_SYSERR; // 3
        }
        return errorcode;
    }

    /** This method is overwritten so that the JML compiler can register its
     *  own tools for the various phases.  This fits in just the right place in 
     *  the compiler initialization and invocation sequence, but I'm not sure how
     *  fragile it is with updates and reorganizations of the code.
     *  
     *  @param args the command-line arguments
     *  @param context the compilation context to use
     *  @return the value to use as the command-line exit code
     */
    //@ requires args != null && \nonnullelements(args);
    //@ requires context != null;
    @Override
    public int compile(String[] args, Context context) {
        this.context = context;
        setOptions(Options.instance(context)); // TODO: Make sure that the parent class's cached options are consistent
        register(context);
        // Note that the Java option processing happens in compile method call below.
        // Those options are not read at the time of the register call,
        // but the register call has to happen before compile is called.
        int exit = super.compile(args,context);
        if (args.length == 0 || Options.instance(context).get(helpOption) != null) {
            helpJML(out);
        }
        return exit;
    }
        
    /** This is a utility method to print out all of the JML help information */
    protected void helpJML(PrintWriter w) {
        w.print(JmlOption.helpInfo());
        w.flush();
    }
    
    /** This method scans the input sequence of command-line arguments, 
     * processing any that are recognized as JML options.  Those options
     * are registered in the Options map.  The String[] returned contains
     * all of the command-line arguments in the input, except those recognized
     * as JML options.
     * @param args the input command-line arguments
     * @param options the Options map in which recognized options are recorded
     * @return all arguments not recognized as JML
     */
    //@ requires \nonnullelements(args);
    //@ ensures \result != null && \nonnullelements(\result);
    String[] processJmlArgs(@NonNull String [] args, @NonNull Options options) {
        java.util.List<String> newargs = new ArrayList<String>();
        java.util.List<String> files = new ArrayList<String>();
        int i = 0;
        while (i<args.length) {
            i = processJmlArg(args,i,options,newargs,files);
        }
        //newargs.addAll(computeDependencyClosure(files));
        newargs.addAll(files);
        return newargs.toArray(new String[newargs.size()]);
    }
    
    // TODO _ document
    public java.util.List<String> computeDependencyClosure(java.util.List<String> files) {
        // fill out dependencies
        java.util.List<String> newargs = new ArrayList<String>();
//        Dependencies d = Dependencies.instance(context);
        Set<String> done = new HashSet<String>();
        java.util.List<String> todo = new LinkedList<String>();
        //JavacFileManager jfm = (JavacFileManager)context.get(JavaFileManager.class);; // FIXME - this causes Lint to be initialized before the Java options are registered
        for (String s: files) {
            // FIXME - don't hardcode this list of suffixes here
            if (Utils.instance(context).hasValidSuffix(s)) {
                todo.add(s);
            }
        }
        while (!todo.isEmpty()) {
            String o = todo.remove(0);
            if (done.contains(o)) continue;
            done.add(o);
//            if (o.getName().endsWith(".java")) newargs.add("C:" + o.toUri().getPath()); // FIXME - a hack to get the full path
            if (o.endsWith(".java")) newargs.add(o); // FIXME FOR REAL - this needs to be made sane
//            Set<JavaFileObject> affected = d.getAffected(o);
//            if (affected != null) {
//                todo.addAll(affected);
//                //Log.instance(context).noticeWriter.println("Adding " + affected.size() + " dependencies for " + o);
//            }
        }
        return newargs;
    }
    
    /** Processes a single JML command-line option and any arguments.
     * 
     * @param args the full array of command-line arguments
     * @param i    the index of the argument to be processed
     * @param options the options object to be adjusted as JML options are found
     * @param remainingArgs any arguments that are not JML options
     * @return the index of the next argument to be processed
     */
    //@ requires \nonnullelements(args);
    //@ requires (* elements of remainingArgs are non-null *);
    //@ requires 0<= i && i< args.length;
    //@ ensures \result > i;
    int processJmlArg(@NonNull String[] args, int i, @NonNull Options options, @NonNull java.util.List<String> remainingArgs, @NonNull java.util.List<String> files ) {
        String res = "";
        String s = args[i++];
        IOption o = JmlOption.find(s);
        if (o == null) {
            int k = s.indexOf('=');
            if (k != -1) {
                o = JmlOption.find(s.substring(0,k));
                if (o != null) {
                    res = s.substring(k+1,s.length());
                    if ("false".equals(res)) res = null;
                    else if ("true".equals(res)) res = "";
                    else if (!o.hasArg()) {
                        // FIXME - problem -  ignoring argument
                    }
                }
            }
            if (s.equals(helpOption)) options.put(s,"");
        } else if (o.hasArg()) {
            if (i < args.length) {
                res = args[i++];
            } else {
                // FIXME - error message
            }
        }
        if (o == null) {
            remainingArgs.add(s);
        } else if (JmlOption.DIR.optionName().equals(s) || JmlOption.DIRS.optionName().equals(s)) {
            java.util.List<File> todo = new LinkedList<File>();
            todo.add(new File(res));
            if (JmlOption.DIRS.optionName().equals(s)) {
                while (i<args.length && (res=args[i]).length() > 0 && res.charAt(0) != '-') {
                    todo.add(new File(res));
                    i++;
                }
            }
            Utils utils = Utils.instance(context);
            while (!todo.isEmpty()) {
                File file = todo.remove(0);
                if (file.isDirectory()) {
                    for (File ff: file.listFiles()) {
                        todo.add(ff);
                    }
                } else if (!file.isFile()) {
                    out.println("Not a file or directory: " + file); // FIXME - make a warning?
                } else if (utils.hasValidSuffix(file.getName())) {
                    files.add(file.toString());
                }
            }
        } else {
            // An empty string is the value of the option if it takes no arguments
            // That is, for boolean options, "" is true, null is false
            options.put(s,res);
        }
        return i;
    }
    
    /** This method is called after options are read, but before tools are registered and 
     * before compilation actually begins; here any additional option
     * processing can be performed.
     */
    // This should be able to be called without difficulty whenever any option
    // is changed
    public void setupOptions() {
        // CAUTION: Do not initialize any of the tools in here
        // If, for example, JmlSpecs gets initialized, then Target will
        // get initialized and it will grab the current version of -target
        // before all the options are set
        Options options = Options.instance(context);
        Utils utils = Utils.instance(context);

        if (options.get(JmlOption.JMLDEBUG.optionName()) != null) {
            utils.jmldebug = true;
            options.put(JmlOption.PROGRESS.optionName(),"");
        }
        
        if (options.get(JmlOption.JMLVERBOSE.optionName()) != null) {
            // ??? FIXME Utils.jmlverbose = true;
            options.put(JmlOption.PROGRESS.optionName(),"");
        }
        
        if (options.get(JmlOption.USEJAVACOMPILER.optionName()) != null) {
            Log.instance(context).noticeWriter.println("The -java option is ignored unless it is the first command-line argument"); // FIXME - change to a warning
        }
        
        if (options.get(JmlOption.PROGRESS.optionName()) != null) {
            if (!progressDelegate.hasDelegate()) progressDelegate.setDelegate(new PrintProgressReporter(context,out));
        }
        
        String cmd = options.get(JmlOption.COMMAND.optionName());
        utils.rac = "rac".equals(cmd) || (cmd == null && options.get(JmlOption.RAC.optionName()) != null);
        utils.esc = "esc".equals(cmd) || (cmd == null && options.get(JmlOption.ESC.optionName()) != null);
        utils.check = "check".equals(cmd) || (cmd == null && options.get(JmlOption.CHECK.optionName()) != null);
        utils.compile = "compile".equals(cmd) || (cmd == null && options.get(JmlOption.COMPILE.optionName()) != null);
        boolean picked = utils.rac||utils.esc||utils.check||utils.compile;
        if (!picked && cmd != null) {
            Log.instance(context).noticeWriter.println("Invalid argument to the -command option: " + cmd); // FIXME - change to a wanring
        }
        if (!picked) utils.check = true;

        String keysString = options.get(JmlOption.KEYS.optionName());
        // TODO - why use Names here?
        utils.commentKeys = new HashSet<Name>();
        if (keysString != null) {
            String[] keys = keysString.split(",");
            for (String k: keys) utils.commentKeys.add(Names.instance(context).fromString(k));
        }
        if (utils.esc) utils.commentKeys.add(Names.instance(context).fromString("ESC")); 
        if (utils.rac) utils.commentKeys.add(Names.instance(context).fromString("RAC")); 
        JmlSpecs.instance(context).initializeSpecsPath();

        if (options.get(JmlOption.NOINTERNALRUNTIME.optionName()) == null) appendRuntime(context);
        
}
    


    /* We override this method in order to process the JML command-line 
     * arguments and to do any tool-specific initialization
     * after the command-line arguments are processed.  
     */   // FIXME - we are hacking the availability of 'context' here in a non-thread-safe way;
        // but note that Main treats options in a non-thread safe way
    // @edu.umd.cs.findbugs.annotations.SuppressWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    @Override
    public List<File> processArgs(String[] args) {
        args = processJmlArgs(args,Options.instance(this.context));
        List<File> files = super.processArgs(args);
        //args.addAll(computeDependencyClosure(files));
        setupOptions();
        return files;
    }
        
    /** This registers the JML versions of many of the tools (e.g. scanner, parser,
     * specifications database,...) used by the compiler.  They must be registered
     * before the Java versions are invoked, since in most cases singleton
     * values are lazily created.  Note also that since tools can only be registered
     * into the context once, circular dependencies can arise from pairs of tools
     * that use each other, if you are not careful.
     * @param context the compilation context into which to register the tools
     */
    public void register(/*@ non_null @*/ Context context) {
        this.context = context;// A hack so that context is available in processArgs()
        if (progressDelegate != null) progressDelegate.setContext(context);
        context.put(IProgressReporter.class,progressDelegate);
        registerTools(context,out,diagListener);
    }
    
    /** Called to register the JML internal tools that replace the tools used
     * in the Java compiler.
     * @param context the compiler context in which the tools are to be used
     * @param out the PrintWriter used for error and informational messages
     */
    public static <S> void registerTools(@NonNull Context context, @NonNull PrintWriter out, @Nullable DiagnosticListener<S> diagListener) {

        // This is done later, but we do it here so that the Log is
        // available if tool registration (or argument processing) needs the
        // Log.  However, note that if the Log is actually instantiated before
        // Java arguments are read, then it is not set consistently with those 
        // options.
        context.put(Log.outKey,out);
        
        if (diagListener != null) context.put(DiagnosticListener.class, diagListener);

        // These have to be first in case there are error messages during 
        // tool registration.
        // FIXME - this call is also made in 'execute' ??
        JavacMessages.instance(context).add(Strings.messagesJML); // registering an additional source of JML-specific error messages

        // These register JML versions of the various tools.  These essentially
        // register factories: no actual instances are created until 
        // instance(context) is called on the particular tool.  Creating instances
        // may trigger a cascade of tool instance generation, which can create
        // tools (such as the Log) before the Options are processed and can
        // trigger some circular dependencies in the constructors of the various
        // tools.
        // Any initialization of these tools that needs to be done based on 
        // options should be performed in processArgs() in this method.
        JmlSpecs.preRegister(context); // registering the specifications repository
        JmlFactory.preRegister(context); // registering a Jml-specific factory from which to generate JmlParsers
        JmlScanner.JmlFactory.preRegister(context); // registering a Jml-specific factory from which to generate JmlScanners
        JmlTree.Maker.preRegister(context); // registering a JML-aware factory for generating JmlTree nodes
        JmlCompiler.preRegister(context);
        JmlEnter.preRegister(context);
        JmlResolve.preRegister(context);
        JmlMemberEnter.preRegister(context);
        JmlFlow.preRegister(context);
        JmlAttr.preRegister(context);  // registering a JML-aware type checker
        JmlCheck.preRegister(context);
        JmlPretty.preRegister(context);
        JmlTreeInfo.preRegister(context);
    }
    
    /** This is overridden so that serious internal bugs are reported as OpenJML
     * rather than Javac bugs.
     */
    // @Override
    protected void bugMessage(Throwable ex) {
        out.println("Internal JML bug - please report.  Build" + JavaCompiler.version());
        ex.printStackTrace(out);
    }
    
    
    // EXTERNAL API FOR PROGRAMATIC ACCESS TO JML COMPILER INTERNALS
    // FIXME - pretty printing needs lots of work
    // FIXME - internal classes for holding specs are not yet finalized
    
    /** Creates and initializes a compiler instance for programmatic use
     * @param args the command line arguments that setup options such as 
     * the class path and the location of specification files; any class file
     * names are ignored; annotation processors should be listed here.
     */
    public Main(@NonNull String[] args) throws Exception {
        this();
        initialize(args);
    }
    
    /** The argument is the array of command-line arguments without files - options only.
     * This should not be called by a client, but only internally.
     * 
     * @param args the array of command-line arguments used to setup options
     */
    protected Main initialize(@NonNull String[] args) throws java.io.IOException {
        if (args == null) args = emptyArgs;
        context = new Context();
        JavacFileManager.preRegister(context); // can't create it until Log has been set up
        Options options = Options.instance(context);
        setOptions(options);
        filenames = new ListBuffer<File>();
        classnames = new ListBuffer<String>();
        register(context);
        Utils.instance(context).findProperties(context);
        for (Map.Entry<Object,Object> entry: System.getProperties().entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith(Strings.optionPropertyPrefix)) {
                key = key.substring(Strings.optionPropertyPrefix.length());
                options.put(key, entry.getValue().toString());
            }
        }

        processArgs(CommandLine.parse(args));
        // FIXME - warn about ignored files? or process them?
        return this;
    }
    
    
    /** Appends the internal runtime directory to the -classpath option.
     */
    static protected void appendRuntime(Context context) {
        boolean verbose = Utils.instance(context).jmldebug ||
            JmlOption.isOption(context,JmlOption.JMLVERBOSE) ||
            Options.instance(context).get("-verbose") != null;
        
        String jmlruntimePath = null;
        
        // See if a jar file has been specified
        
        //if (jmlruntimePath == null) {
            String sy = System.getProperty(Strings.defaultRuntimeClassPath);
            // These are used in testing - sy should be the directory of the OpenJML project
            if (sy != null) {
                jmlruntimePath = sy;
            }
        //}

        // Then look for jmlruntime.jar in the classpath itself
        
        if (jmlruntimePath == null) {
            URL url = ClassLoader.getSystemResource(Strings.runtimeJarName);
            if (url != null) {
                jmlruntimePath = url.getFile();
                if (jmlruntimePath.startsWith("file:/")) {
                    jmlruntimePath = jmlruntimePath.substring("file:/".length());
                }
            }
        }
        
        // Then look for something in the same directory as something on the classpath
        
        String classpath = System.getProperty("java.class.path");
        if (jmlruntimePath == null) {
            String[] ss = classpath.split(java.io.File.pathSeparator);
            for (String s: ss) {
                if (s.endsWith(".jar")) {
                    try {
                        File f = new File(s).getCanonicalFile().getParentFile();
                        if (f != null) {
                            f = new File(f,Strings.runtimeJarName);
                            if (f.isFile()) {
                                jmlruntimePath = f.getPath();
                                break;
                            }
                        }
                    } catch (IOException e) {
                        // Just skip
                    }
                } else {
                    File f = new File(new File(s),Strings.runtimeJarName);
                    if (f.isFile()) {
                        jmlruntimePath = f.getPath();
                        break;
                    }
                }
            }
        }
        
        // The above all presume some variation on the conventional installation
        // of the command-line tool.  In the development environment, those
        // presumptions do not hold.  So in that case we use the appropriate
        // bin directories directly. We make sure that we get both of them.
        // This also takes care of the case in which the openjml.jar file is in 
        // the class path under a different name.

        if (jmlruntimePath == null) {
            URL url = ClassLoader.getSystemResource("org/jmlspecs/annotation");
            if (url != null) {
                try {
                    String s = url.getPath();
                    if (s.startsWith("file:")) s = s.substring("file:".length());
                    int b = (s.length()> 2 && s.charAt(0) == '/' && s.charAt(2) == ':') ? 1 : 0;
                    int k = s.indexOf("!");
                    if (k >= 0) {
                        s = s.substring(b,k);
                    } else {
                        s = s.substring(b);
                        s = new File(s).getParentFile().getParentFile().getParent();
                    }
                    if (new File(s).exists()) jmlruntimePath = s;

                    url = ClassLoader.getSystemResource("org/jmlspecs/lang");
                    if (url != null) {
                        s = url.getPath();
                        if (s.startsWith("file:")) s = s.substring("file:".length());
                        b = (s.length()> 2 && s.charAt(0) == '/' && s.charAt(2) == ':') ? 1 : 0;
                        k = s.indexOf("!");
                        if (k >= 0) {
                            s = s.substring(b,k);
                        } else {
                            s = s.substring(b);
                            s = new File(s).getParentFile().getParentFile().getParent();
                        }
                        if (new File(s).exists() && !s.equals(jmlruntimePath)) jmlruntimePath = jmlruntimePath + java.io.File.pathSeparator + s;
                    }
                } catch (Exception e) {
                    // Just skip
                }
            }
        }

        if (jmlruntimePath != null) {
            if (verbose) Log.instance(context).noticeWriter.println("Using internal runtime " + jmlruntimePath);
            String cp = Options.instance(context).get("-classpath");
            if (cp == null) cp = System.getProperty("java.class.path");
            cp = cp==null ? jmlruntimePath : (cp + java.io.File.pathSeparator + jmlruntimePath);
            Options.instance(context).put("-classpath",cp);
            if (verbose) Log.instance(context).noticeWriter.println("Classpath: " + Options.instance(context).get("-classpath"));
        } else {
            Log.instance(context).warning("jml.no.internal.runtime");
        }
    }
    
    /** An empty array used simply to avoid repeatedly creating one. */
    private static final @NonNull String[] emptyArgs = new String[]{};
    
    /** Returns a reference to the API's compilation context. */
    public @Nullable Context context() {
        return context;
    }
    

}

// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package qunar.tc.decompiler.main.rels;

import qunar.tc.decompiler.code.CodeConstants;
import qunar.tc.decompiler.code.InstructionSequence;
import qunar.tc.decompiler.code.cfg.ControlFlowGraph;
import qunar.tc.decompiler.main.DecompilerContext;
import qunar.tc.decompiler.main.collectors.CounterContainer;
import qunar.tc.decompiler.main.extern.IFernflowerLogger;
import qunar.tc.decompiler.main.extern.IFernflowerPreferences;
import qunar.tc.decompiler.modules.code.DeadCodeHelper;
import qunar.tc.decompiler.modules.decompiler.*;
import qunar.tc.decompiler.modules.decompiler.deobfuscator.ExceptionDeobfuscator;
import qunar.tc.decompiler.modules.decompiler.stats.RootStatement;
import qunar.tc.decompiler.modules.decompiler.vars.VarProcessor;
import qunar.tc.decompiler.struct.StructClass;
import qunar.tc.decompiler.struct.StructMethod;
import qunar.tc.decompiler.struct.gen.MethodDescriptor;

import java.io.IOException;

public class MethodProcessorRunnable implements Runnable {
    public final Object lock = new Object();

    private final StructMethod method;
    private final MethodDescriptor methodDescriptor;
    private final VarProcessor varProc;
    private final DecompilerContext parentContext;

    private volatile RootStatement root;
    private volatile Throwable error;
    private volatile boolean finished = false;

    public MethodProcessorRunnable(StructMethod method,
                                   MethodDescriptor methodDescriptor,
                                   VarProcessor varProc,
                                   DecompilerContext parentContext) {
        this.method = method;
        this.methodDescriptor = methodDescriptor;
        this.varProc = varProc;
        this.parentContext = parentContext;
    }

    @Override
    public void run() {
        error = null;
        root = null;

        try {
            DecompilerContext.setCurrentContext(parentContext);
            root = codeToJava(method, methodDescriptor, varProc);
        } catch (Throwable t) {
            error = t;
        } finally {
            DecompilerContext.setCurrentContext(null);
        }

        finished = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public static RootStatement codeToJava(StructMethod mt, MethodDescriptor md, VarProcessor varProc) throws IOException {
        StructClass cl = mt.getClassStruct();

        boolean isInitializer = CodeConstants.CLINIT_NAME.equals(mt.getName()); // for now static initializer only

        mt.expandData();
        InstructionSequence seq = mt.getInstructionSequence();
        ControlFlowGraph graph = new ControlFlowGraph(seq);

        DeadCodeHelper.removeDeadBlocks(graph);
        graph.inlineJsr(mt);

        // TODO: move to the start, before jsr inlining
        DeadCodeHelper.connectDummyExitBlock(graph);

        DeadCodeHelper.removeGotos(graph);

        ExceptionDeobfuscator.removeCircularRanges(graph);

        ExceptionDeobfuscator.restorePopRanges(graph);

        if (DecompilerContext.getOption(IFernflowerPreferences.REMOVE_EMPTY_RANGES)) {
            ExceptionDeobfuscator.removeEmptyRanges(graph);
        }

        if (DecompilerContext.getOption(IFernflowerPreferences.NO_EXCEPTIONS_RETURN)) {
            // special case: single return instruction outside of a protected range
            DeadCodeHelper.incorporateValueReturns(graph);
        }

        //		ExceptionDeobfuscator.restorePopRanges(graph);
        ExceptionDeobfuscator.insertEmptyExceptionHandlerBlocks(graph);

        DeadCodeHelper.mergeBasicBlocks(graph);

        DecompilerContext.getCounterContainer().setCounter(CounterContainer.VAR_COUNTER, mt.getLocalVariables());

        if (ExceptionDeobfuscator.hasObfuscatedExceptions(graph)) {
            DecompilerContext.getLogger().writeMessage("Heavily obfuscated exception ranges found!", IFernflowerLogger.Severity.WARN);
        }

        RootStatement root = DomHelper.parseGraph(graph);

        FinallyProcessor fProc = new FinallyProcessor(md, varProc);
        while (fProc.iterateGraph(mt, root, graph)) {
            root = DomHelper.parseGraph(graph);
        }

        // remove synchronized exception handler
        // not until now because of comparison between synchronized statements in the finally cycle
        DomHelper.removeSynchronizedHandler(root);

        //		LabelHelper.lowContinueLabels(root, new HashSet<StatEdge>());

        SequenceHelper.condenseSequences(root);

        ClearStructHelper.clearStatements(root);

        ExprProcessor proc = new ExprProcessor(md, varProc);
        proc.processStatement(root, cl);

        SequenceHelper.condenseSequences(root);

        StackVarsProcessor stackProc = new StackVarsProcessor();

        do {
            stackProc.simplifyStackVars(root, mt, cl);
            varProc.setVarVersions(root);
        }
        while (new PPandMMHelper().findPPandMM(root));

        while (true) {
            LabelHelper.cleanUpEdges(root);

            do {
                MergeHelper.enhanceLoops(root);
            }
            while (LoopExtractHelper.extractLoops(root) || IfHelper.mergeAllIfs(root));

            if (DecompilerContext.getOption(IFernflowerPreferences.IDEA_NOT_NULL_ANNOTATION)) {
                if (IdeaNotNullHelper.removeHardcodedChecks(root, mt)) {
                    SequenceHelper.condenseSequences(root);
                    stackProc.simplifyStackVars(root, mt, cl);
                    varProc.setVarVersions(root);
                }
            }

            LabelHelper.identifyLabels(root);

            if (InlineSingleBlockHelper.inlineSingleBlocks(root)) {
                continue;
            }

            // initializer may have at most one return point, so no transformation of method exits permitted
            if (isInitializer || !ExitHelper.condenseExits(root)) {
                break;
            }

            // FIXME: !!
            //if(!EliminateLoopsHelper.eliminateLoops(root)) {
            //  break;
            //}
        }

        ExitHelper.removeRedundantReturns(root);

        SecondaryFunctionsHelper.identifySecondaryFunctions(root, varProc);

        varProc.setVarDefinitions(root);

        // must be the last invocation, because it makes the statement structure inconsistent
        // FIXME: new edge type needed
        LabelHelper.replaceContinueWithBreak(root);

        mt.releaseResources();

        return root;
    }

    public RootStatement getResult() throws Throwable {
        Throwable t = error;
        if (t != null) throw t;
        return root;
    }

    public boolean isFinished() {
        return finished;
    }
}
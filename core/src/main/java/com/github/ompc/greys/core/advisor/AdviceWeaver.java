package com.github.ompc.greys.core.advisor;

import com.github.ompc.greys.core.util.AsmCodeLock;
import com.github.ompc.greys.core.util.CodeLock;
import com.github.ompc.greys.core.util.LogUtil;
import com.github.ompc.greys.core.util.Matcher;
import com.github.ompc.greys.core.util.affect.EnhancerAffect;
import com.github.ompc.greys.core.util.collection.GaStack;
import com.github.ompc.greys.core.util.collection.ThreadUnsafeFixGaStack;
import com.github.ompc.greys.core.util.collection.ThreadUnsafeGaStack;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.ompc.greys.core.GlobalOptions.isDebugForAsm;
import static com.github.ompc.greys.core.util.GaCheckUtils.isEquals;
import static com.github.ompc.greys.core.util.GaStringUtils.tranClassName;
import static java.lang.Thread.currentThread;


/**
 * 用于Tracing的代码锁
 */
class TracingAsmCodeLock extends AsmCodeLock {

    public TracingAsmCodeLock(AdviceAdapter aa) {
        super(
                aa,
                new int[]{
                        ACONST_NULL, ICONST_0, ICONST_1, SWAP, SWAP, POP2, POP
                },
                new int[]{
                        ICONST_1, ACONST_NULL, ICONST_0, SWAP, SWAP, POP, POP2
                }
        );
    }
}

/**
 * 通知编织者<br/>
 * <p/>
 * <h2>线程帧栈与执行帧栈</h2>
 * 编织者在执行通知的时候有两个重要的栈:线程帧栈(threadFrameStack),执行帧栈(frameStack)
 * <p/>
 * Created by oldmanpushcart@gmail.com on 15/5/17.
 */
public class AdviceWeaver extends ClassVisitor implements Opcodes {

    private final static Logger logger = LogUtil.getLogger();

    // 线程帧栈堆栈大小
    private final static int FRAME_STACK_SIZE = 7;

    // 通知监听器集合
    private final static Map<Integer/*ADVICE_ID*/, AdviceListener> advices
            = new ConcurrentHashMap<Integer, AdviceListener>();

    // 线程帧封装
    private static final Map<Thread, GaStack<GaStack<Object>>> threadBoundContexts
            = new ConcurrentHashMap<Thread, GaStack<GaStack<Object>>>();

    // 防止自己递归调用
    private static final ThreadLocal<Boolean> isSelfCallRef = new ThreadLocal<Boolean>() {

        @Override
        protected Boolean initialValue() {
            return false;
        }

    };


    /**
     * 方法开始<br/>
     * 用于编织通知器,外部不会直接调用
     *
     * @param loader     类加载器
     * @param adviceId   通知ID
     * @param className  类名
     * @param methodName 方法名
     * @param methodDesc 方法描述
     * @param target     返回结果
     *                   若为无返回值方法(void),则为null
     * @param args       参数列表
     */
    public static void methodOnBegin(
            int adviceId,
            ClassLoader loader, String className, String methodName, String methodDesc,
            Object target, Object[] args) {

        if (isSelfCallRef.get()) {
            return;
        } else {
            isSelfCallRef.set(true);
        }

        try {
            // 构建执行帧栈,保护当前的执行现场
            final GaStack<Object> frameStack = new ThreadUnsafeFixGaStack<Object>(FRAME_STACK_SIZE);
            frameStack.push(loader);
            frameStack.push(className);
            frameStack.push(methodName);
            frameStack.push(methodDesc);
            frameStack.push(target);
            frameStack.push(args);

            final AdviceListener listener = getListener(adviceId);
            frameStack.push(listener);

            // 获取通知器并做前置通知
            before(listener, loader, className, methodName, methodDesc, target, args);

            // 保护当前执行帧栈,压入线程帧栈
            threadFrameStackPush(frameStack);
        } finally {
            isSelfCallRef.set(false);
        }

    }


    /**
     * 方法以返回结束<br/>
     * 用于编织通知器,外部不会直接调用
     *
     * @param returnObject 返回对象
     *                     若目标为静态方法,则为null
     */
    public static void methodOnReturnEnd(Object returnObject) {
        methodOnEnd(false, returnObject);
    }

    /**
     * 方法以抛异常结束<br/>
     * 用于编织通知器,外部不会直接调用
     *
     * @param throwable 抛出异常
     */
    public static void methodOnThrowingEnd(Throwable throwable) {
        methodOnEnd(true, throwable);
    }

    /**
     * 所有的返回都统一处理
     *
     * @param isThrowing        标记正常返回结束还是抛出异常结束
     * @param returnOrThrowable 正常返回或者抛出异常对象
     */
    private static void methodOnEnd(boolean isThrowing, Object returnOrThrowable) {

        if (isSelfCallRef.get()) {
            return;
        } else {
            isSelfCallRef.set(true);
        }

        try {
            // 弹射线程帧栈,恢复Begin所保护的执行帧栈
            final GaStack<Object> frameStack = threadFrameStackPop();

            // 弹射执行帧栈,恢复Begin所保护的现场
            final AdviceListener listener = (AdviceListener) frameStack.pop();
            final Object[] args = (Object[]) frameStack.pop();
            final Object target = frameStack.pop();
            final String methodDesc = (String) frameStack.pop();
            final String methodName = (String) frameStack.pop();
            final String className = (String) frameStack.pop();
            final ClassLoader loader = (ClassLoader) frameStack.pop();

            // 异常通知
            if (isThrowing) {
                afterThrowing(listener, loader, className, methodName, methodDesc, target, args, (Throwable) returnOrThrowable);
            }

            // 返回通知
            else {
                afterReturning(listener, loader, className, methodName, methodDesc, target, args, returnOrThrowable);
            }
        } finally {
            isSelfCallRef.set(false);
        }

    }

    /**
     * 方法内部调用开始
     *
     * @param adviceId 通知ID
     * @param owner    调用类名
     * @param name     调用方法名
     * @param desc     调用方法描述
     */
    public static void methodOnInvokeBeforeTracing(int adviceId, String owner, String name, String desc) {
        final InvokeTraceable listener = (InvokeTraceable) getListener(adviceId);
        if (null != listener) {
            try {
                listener.invokeBeforeTracing(owner, name, desc);
            } catch (Throwable t) {
                logger.warn("advice before tracing failed.", t);
            }
        }
    }

    /**
     * 方法内部调用结束(正常返回)
     *
     * @param adviceId 通知ID
     * @param owner    调用类名
     * @param name     调用方法名
     * @param desc     调用方法描述
     */
    public static void methodOnInvokeAfterTracing(int adviceId, String owner, String name, String desc) {
        final InvokeTraceable listener = (InvokeTraceable) getListener(adviceId);
        if (null != listener) {
            try {
                listener.invokeAfterTracing(owner, name, desc);
            } catch (Throwable t) {
                logger.warn("advice after tracing failed.", t);
            }
        }
    }


    /*
     * 线程帧栈压栈<br/>
     * 将当前执行帧栈压入线程栈
     */
    private static void threadFrameStackPush(GaStack<Object> frameStack) {
        final Thread thread = currentThread();
        GaStack<GaStack<Object>> threadFrameStack = threadBoundContexts.get(thread);
        if (null == threadFrameStack) {
            threadBoundContexts.put(thread, threadFrameStack = new ThreadUnsafeGaStack<GaStack<Object>>());
        }
        threadFrameStack.push(frameStack);
    }

    private static GaStack<Object> threadFrameStackPop() {
        return threadBoundContexts.get(currentThread()).pop();
    }

    private static AdviceListener getListener(int adviceId) {
        return advices.get(adviceId);
    }


    /**
     * 注册监听器
     *
     * @param adviceId 通知ID
     * @param listener 通知监听器
     */
    public static void reg(int adviceId, AdviceListener listener) {

        // 触发监听器创建
        listener.create();

        // 注册监听器
        advices.put(adviceId, listener);
    }

    /**
     * 注销监听器
     *
     * @param adviceId 通知ID
     */
    public static void unReg(int adviceId) {

        // 注销监听器
        final AdviceListener listener = advices.remove(adviceId);

        // 触发监听器销毁
        if (null != listener) {
            listener.destroy();
        }

    }

    private static void before(AdviceListener listener,
                               ClassLoader loader, String className, String methodName, String methodDesc,
                               Object target, Object[] args) {

        if (null != listener) {
            try {
                listener.before(loader, className, methodName, methodDesc, target, args);
            } catch (Throwable t) {
                logger.warn("advice before failed.", t);
            }
        }

    }

    private static void afterReturning(AdviceListener listener,
                                       ClassLoader loader, String className, String methodName, String methodDesc,
                                       Object target, Object[] args, Object returnObject) {
        if (null != listener) {
            try {
                listener.afterReturning(loader, className, methodName, methodDesc, target, args, returnObject);
            } catch (Throwable t) {
                logger.warn("advice returning failed.", t);
            }
        }
    }

    private static void afterThrowing(AdviceListener listener,
                                      ClassLoader loader, String className, String methodName, String methodDesc,
                                      Object target, Object[] args, Throwable throwable) {
        if (null != listener) {
            try {
                listener.afterThrowing(loader, className, methodName, methodDesc, target, args, throwable);
            } catch (Throwable t) {
                logger.warn("advice throwing failed.", t);
            }
        }
    }


    private final int adviceId;
    private final boolean isTracing;
    private final String className;
    private final Matcher matcher;
    private final EnhancerAffect affect;


    /**
     * 构建通知编织器
     *
     * @param adviceId  通知ID
     * @param isTracing 可跟踪方法调用
     * @param className 类名称
     * @param matcher   方法匹配
     *                  只有匹配上的方法才会被织入通知器
     * @param affect    影响计数
     * @param cv        ClassVisitor for ASM
     */
    public AdviceWeaver(int adviceId, boolean isTracing, String className, Matcher matcher, EnhancerAffect affect, ClassVisitor cv) {
        super(ASM5, cv);
        this.adviceId = adviceId;
        this.isTracing = isTracing;
        this.className = className;
        this.matcher = matcher;
        this.affect = affect;
    }

    /**
     * 是否抽象属性
     */
    private boolean isAbstract(int access) {
        return (ACC_ABSTRACT & access) == ACC_ABSTRACT;
    }


    /**
     * 是否需要忽略
     */
    private boolean isIgnore(MethodVisitor mv, int access, String methodName) {
        return null == mv
                || isAbstract(access)
                || !matcher.matching(methodName)
                || isEquals(methodName, "<clinit>");
    }

    @Override
    public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String desc,
            final String signature,
            final String[] exceptions) {

        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

        if (isIgnore(mv, access, name)) {
            return mv;
        }

        // 编织方法计数
        affect.mCnt(1);

        return new AdviceAdapter(ASM5, new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions), access, name, desc) {

            // -- Lebel for try...catch block
            private final Label beginLabel = new Label();
            private final Label endLabel = new Label();

            // -- KEY of advice --
            private final int KEY_GREYS_ADVICE_BEFORE_METHOD = 0;
            private final int KEY_GREYS_ADVICE_RETURN_METHOD = 1;
            private final int KEY_GREYS_ADVICE_THROWS_METHOD = 2;
            private final int KEY_GREYS_ADVICE_BEFORE_INVOKING_METHOD = 3;
            private final int KEY_GREYS_ADVICE_AFTER_INVOKING_METHOD = 4;


            // -- KEY of ASM_TYPE or ASM_METHOD --
            private final Type ASM_TYPE_SPY = Type.getType("Lcom/github/ompc/greys/agent/Spy;");
            private final Type ASM_TYPE_OBJECT = Type.getType(Object.class);
            private final Type ASM_TYPE_OBJECT_ARRAY = Type.getType(Object[].class);
            private final Type ASM_TYPE_CLASS = Type.getType(Class.class);
            private final Type ASM_TYPE_INTEGER = Type.getType(Integer.class);
            private final Type ASM_TYPE_CLASS_LOADER = Type.getType(ClassLoader.class);
            private final Type ASM_TYPE_STRING = Type.getType(String.class);
            private final Type ASM_TYPE_THROWABLE = Type.getType(Throwable.class);
            private final Type ASM_TYPE_INT = Type.getType(int.class);
            private final Type ASM_TYPE_METHOD = Type.getType(java.lang.reflect.Method.class);
            private final Method ASM_METHOD_METHOD_INVOKE = Method.getMethod("Object invoke(Object,Object[])");

            // 代码锁
            private final CodeLock codeLockForTracing = new TracingAsmCodeLock(this);


            private void _debug(final StringBuilder append, final String msg) {

                if (!isDebugForAsm) {
                    return;
                }

                // println msg
                visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                if(StringUtils.isBlank(append.toString()) ) {
                    visitLdcInsn(append.append(msg).toString());
                } else {
                    visitLdcInsn(append.append(" >> ").append(msg).toString());
                }

                visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            }

//            private void _debug_dup(final String msg) {
//
//                if (!isDebugForAsm) {
//                    return;
//                }
//
//                // print prefix
//                visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
//                visitLdcInsn(msg);
//                visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
//
//                // println msg
//                dup();
//                visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
//                swap();
//                visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
//                visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
//            }

            /**
             * 加载通知方法
             * @param keyOfMethod 通知方法KEY
             */
            private void loadAdviceMethod(int keyOfMethod) {

                switch (keyOfMethod) {

                    case KEY_GREYS_ADVICE_BEFORE_METHOD: {
                        getStatic(ASM_TYPE_SPY, "ON_BEFORE_METHOD", ASM_TYPE_METHOD);
                        break;
                    }

                    case KEY_GREYS_ADVICE_RETURN_METHOD: {
                        getStatic(ASM_TYPE_SPY, "ON_RETURN_METHOD", ASM_TYPE_METHOD);
                        break;
                    }

                    case KEY_GREYS_ADVICE_THROWS_METHOD: {
                        getStatic(ASM_TYPE_SPY, "ON_THROWS_METHOD", ASM_TYPE_METHOD);
                        break;
                    }

                    case KEY_GREYS_ADVICE_BEFORE_INVOKING_METHOD: {
                        getStatic(ASM_TYPE_SPY, "BEFORE_INVOKING_METHOD", ASM_TYPE_METHOD);
                        break;
                    }

                    case KEY_GREYS_ADVICE_AFTER_INVOKING_METHOD: {
                        getStatic(ASM_TYPE_SPY, "AFTER_INVOKING_METHOD", ASM_TYPE_METHOD);
                        break;
                    }

                    default: {
                        throw new IllegalArgumentException("illegal keyOfMethod=" + keyOfMethod);
                    }

                }

            }

            /**
             * 加载ClassLoader<br/>
             * 这里分开静态方法中ClassLoader的获取以及普通方法中ClassLoader的获取
             * 主要是性能上的考虑
             */
            private void loadClassLoader() {

                if (this.isStaticMethod()) {
                    visitLdcInsn(tranClassName(className));
                    invokeStatic(ASM_TYPE_CLASS, Method.getMethod("Class forName(String)"));
                    invokeVirtual(ASM_TYPE_CLASS, Method.getMethod("ClassLoader getClassLoader()"));

                } else {
                    loadThis();
                    invokeVirtual(ASM_TYPE_OBJECT, Method.getMethod("Class getClass()"));
                    invokeVirtual(ASM_TYPE_CLASS, Method.getMethod("ClassLoader getClassLoader()"));
                }

            }

            /**
             * 加载before通知参数数组
             */
            private void loadArrayForBefore() {
                push(7);
                newArray(ASM_TYPE_OBJECT);

                dup();
                push(0);
                push(adviceId);
                box(ASM_TYPE_INT);
                arrayStore(ASM_TYPE_INTEGER);

                dup();
                push(1);
                loadClassLoader();
                arrayStore(ASM_TYPE_CLASS_LOADER);

                dup();
                push(2);
                push(className);
                arrayStore(ASM_TYPE_STRING);

                dup();
                push(3);
                push(name);
                arrayStore(ASM_TYPE_STRING);

                dup();
                push(4);
                push(desc);
                arrayStore(ASM_TYPE_STRING);

                dup();
                push(5);
                loadThisOrPushNullIfIsStatic();
                arrayStore(ASM_TYPE_OBJECT);

                dup();
                push(6);
                loadArgArray();
                arrayStore(ASM_TYPE_OBJECT_ARRAY);
            }


            @Override
            protected void onMethodEnter() {

                codeLockForTracing.lock(new CodeLock.Block() {
                    @Override
                    public void code() {

                        final StringBuilder append = new StringBuilder();
                        _debug(append, "debug:onMethodEnter()");

                        // 加载before方法
                        loadAdviceMethod(KEY_GREYS_ADVICE_BEFORE_METHOD);
                        _debug(append, "loadAdviceMethod()");

                        // 推入Method.invoke()的第一个参数
                        pushNull();

                        // 方法参数
                        loadArrayForBefore();
                        _debug(append, "loadArrayForBefore()");

                        // 调用方法
                        invokeVirtual(ASM_TYPE_METHOD, ASM_METHOD_METHOD_INVOKE);
                        pop();
                        _debug(append, "invokeVirtual()");

                    }
                });

                mark(beginLabel);

            }


            /*
             * 加载return通知参数数组
             */
            private void loadReturnArgs() {
                dup2X1();
                pop2();
                push(1);
                newArray(ASM_TYPE_OBJECT);
                dup();
                dup2X1();
                pop2();
                push(0);
                swap();
                arrayStore(ASM_TYPE_OBJECT);
            }

            @Override
            protected void onMethodExit(final int opcode) {

                if (!isThrow(opcode)) {
                    codeLockForTracing.lock(new CodeLock.Block() {
                        @Override
                        public void code() {

                            final StringBuilder append = new StringBuilder();
                            _debug(append, "debug:onMethodExit()");

                            // 加载返回对象
                            loadReturn(opcode);
                            _debug(append, "loadReturn()");

                            // 加载returning方法
                            loadAdviceMethod(KEY_GREYS_ADVICE_RETURN_METHOD);
                            _debug(append, "loadAdviceMethod()");

                            // 推入Method.invoke()的第一个参数
                            pushNull();

                            // 加载return通知参数数组
                            loadReturnArgs();
                            _debug(append, "loadReturnArgs()");

                            invokeVirtual(ASM_TYPE_METHOD, ASM_METHOD_METHOD_INVOKE);
                            pop();
                            _debug(append, "invokeVirtual()");

                        }
                    });
                }

            }


            /*
             * 创建throwing通知参数本地变量
             */
            private void loadThrowArgs() {
                dup2X1();
                pop2();
                push(1);
                newArray(ASM_TYPE_OBJECT);
                dup();
                dup2X1();
                pop2();
                push(0);
                swap();
                arrayStore(ASM_TYPE_THROWABLE);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {

                mark(endLabel);
                catchException(beginLabel, endLabel, ASM_TYPE_THROWABLE);

                codeLockForTracing.lock(new CodeLock.Block() {
                    @Override
                    public void code() {

                        final StringBuilder append = new StringBuilder();
                        _debug(append, "debug:catchException()");

                        // 加载异常
                        loadThrow();
                        _debug(append, "loadAdviceMethod()");

                        // 加载throwing方法
                        loadAdviceMethod(KEY_GREYS_ADVICE_THROWS_METHOD);
                        _debug(append, "loadAdviceMethod()");

                        // 推入Method.invoke()的第一个参数
                        pushNull();

                        // 加载throw通知参数数组
                        loadThrowArgs();
                        _debug(append, "loadThrowArgs()");

                        // 调用方法
                        invokeVirtual(ASM_TYPE_METHOD, ASM_METHOD_METHOD_INVOKE);
                        pop();
                        _debug(append, "invokeVirtual()");

                    }
                });

                throwException();

                super.visitMaxs(maxStack, maxLocals);
            }

            /**
             * 是否静态方法
             * @return true:静态方法 / false:非静态方法
             */
            private boolean isStaticMethod() {
                return (methodAccess & ACC_STATIC) != 0;
            }

            /**
             * 是否抛出异常返回(通过字节码判断)
             * @param opcode 操作码
             * @return true:以抛异常形式返回 / false:非抛异常形式返回(return)
             */
            private boolean isThrow(int opcode) {
                return opcode == ATHROW;
            }

            /**
             * 将NULL推入堆栈
             */
            private void pushNull() {
                push((Type) null);
            }

            /**
             * 加载this/null
             */
            private void loadThisOrPushNullIfIsStatic() {
                if (isStaticMethod()) {
                    pushNull();
                } else {
                    loadThis();
                }
            }

            /**
             * 加载返回值
             * @param opcode 操作吗
             */
            private void loadReturn(int opcode) {
                switch (opcode) {

                    case RETURN: {
                        pushNull();
                        break;
                    }

                    case ARETURN: {
                        dup();
                        break;
                    }

                    case LRETURN:
                    case DRETURN: {
                        dup2();
                        box(Type.getReturnType(methodDesc));
                        break;
                    }

                    default: {
                        dup();
                        box(Type.getReturnType(methodDesc));
                        break;
                    }

                }
            }

            /**
             * 加载异常
             */
            private void loadThrow() {
                dup();
            }


            /**
             * 加载方法调用跟踪通知所需参数数组
             */
            private void loadArrayForInvokeTracing(String owner, String name, String desc) {
                push(4);
                newArray(ASM_TYPE_OBJECT);

                dup();
                push(0);
                push(adviceId);
                box(ASM_TYPE_INT);
                arrayStore(ASM_TYPE_INTEGER);

                dup();
                push(1);
                push(owner);
                arrayStore(ASM_TYPE_STRING);

                dup();
                push(2);
                push(name);
                arrayStore(ASM_TYPE_STRING);

                dup();
                push(3);
                push(desc);
                arrayStore(ASM_TYPE_STRING);
            }


            @Override
            public void visitInsn(int opcode) {
                super.visitInsn(opcode);
                codeLockForTracing.code(opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, final String owner, final String name, final String desc, boolean itf) {

                // 方法调用前通知
                if (isTracing && !codeLockForTracing.isLock()) {
                    codeLockForTracing.lock(new CodeLock.Block() {
                        @Override
                        public void code() {

                            final StringBuilder append = new StringBuilder();
                            _debug(append, "debug:beforeInvoking()");

                            loadAdviceMethod(KEY_GREYS_ADVICE_BEFORE_INVOKING_METHOD);
                            _debug(append, "loadAdviceMethod()");

                            pushNull();
                            loadArrayForInvokeTracing(owner, name, desc);
                            _debug(append, "loadArrayForInvokeTracing()");

                            invokeVirtual(ASM_TYPE_METHOD, ASM_METHOD_METHOD_INVOKE);
                            pop();
                            _debug(append, "invokeVirtual()");

                        }
                    });
                }

                // 方法执行
                super.visitMethodInsn(opcode, owner, name, desc, itf);

                // 方法调用后通知
                if (isTracing && !codeLockForTracing.isLock()) {
                    codeLockForTracing.lock(new CodeLock.Block() {
                        @Override
                        public void code() {

                            final StringBuilder append = new StringBuilder();
                            _debug(append, "debug:afterInvoking()");

                            loadAdviceMethod(KEY_GREYS_ADVICE_AFTER_INVOKING_METHOD);
                            _debug(append, "loadAdviceMethod()");

                            pushNull();
                            loadArrayForInvokeTracing(owner, name, desc);
                            _debug(append, "loadArrayForInvokeTracing()");

                            invokeVirtual(ASM_TYPE_METHOD, ASM_METHOD_METHOD_INVOKE);
                            pop();
                            _debug(append, "invokeVirtual()");

                        }
                    });
                }

            }

        };

    }


}

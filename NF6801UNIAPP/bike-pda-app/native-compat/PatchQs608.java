import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** 可复现地追加兼容层，厂商原始 AAR 保留在 vendor-source。 */
public final class PatchQs608 implements Opcodes {
    private static final String BRIDGE = "com/bike/pda/hardware/Qs608Hardware";
    private static final String CALLBACK = "io/dcloud/feature/uniapp/bridge/UniJSCallback";
    private static final String PRINT = "com/qs/n601/utils/PrintUtils";
    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Paths.get(args[0]));
        Map<String, byte[]> aar = readZip(original);
        Map<String, byte[]> classes = readZip(aar.get("classes.jar"));
        String module = "com/qs/n601/UpdateModule.class";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(classes.get(module)).accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((name.equals("init") && descriptor.equals("(Ljava/lang/String;ZI)V"))
                        || (name.equals("scan") && descriptor.equals("(Ljava/lang/String;L" + CALLBACK + ";)V"))) return null;
                MethodVisitor originalMethod = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("printPaper")) return new MethodVisitor(ASM9, originalMethod) {
                    @Override public void visitMethodInsn(int opcode, String owner, String methodName, String desc, boolean isInterface) {
                        if (owner.equals("java/lang/Exception") && methodName.equals("printStackTrace") && desc.equals("()V")) {
                            // 原插件只弹 Toast，JS 不知道渲染失败；同时保存可查询的打印失败状态。
                            super.visitInsn(DUP);
                            super.visitMethodInsn(INVOKESTATIC, BRIDGE, "failPrint", "(Ljava/lang/Exception;)V", false);
                        }
                        super.visitMethodInsn(opcode, owner, methodName, desc, isInterface);
                    }
                };
                return originalMethod;
            }
            private MethodVisitor method(String name, String descriptor) {
                MethodVisitor method = super.visitMethod(ACC_PUBLIC, name, descriptor, null, null);
                AnnotationVisitor annotation = method.visitAnnotation("Lio/dcloud/feature/uniapp/annotation/UniJSMethod;", true);
                annotation.visit("uiThread", true);
                annotation.visitEnd();
                method.visitCode();
                return method;
            }
            @Override public void visitEnd() {
                MethodVisitor init = method("init", "(Ljava/lang/String;ZI)V");
                init.visitVarInsn(ALOAD, 0);
                init.visitFieldInsn(GETFIELD, "com/qs/n601/UpdateModule", "mWXSDKInstance", "Lcom/taobao/weex/WXSDKInstance;");
                init.visitMethodInsn(INVOKEVIRTUAL, "com/taobao/weex/WXSDKInstance", "getContext", "()Landroid/content/Context;", false);
                init.visitVarInsn(ILOAD, 2);
                init.visitVarInsn(ILOAD, 3);
                init.visitMethodInsn(INVOKESTATIC, BRIDGE, "open", "(Landroid/content/Context;ZI)V", false);
                init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
                MethodVisitor scan = method("scan", "(Ljava/lang/String;L" + CALLBACK + ";)V");
                scan.visitVarInsn(ALOAD, 2);
                scan.visitMethodInsn(INVOKESTATIC, BRIDGE, "requestScan", "()Lorg/json/JSONObject;", false);
                scan.visitMethodInsn(INVOKEVIRTUAL, "org/json/JSONObject", "toString", "()Ljava/lang/String;", false);
                scan.visitMethodInsn(INVOKEINTERFACE, CALLBACK, "invoke", "(Ljava/lang/Object;)V", true);
                scan.visitInsn(RETURN); scan.visitMaxs(0, 0); scan.visitEnd();
                MethodVisitor status = method("hardwareStatus", "(L" + CALLBACK + ";)V");
                status.visitVarInsn(ALOAD, 1);
                status.visitMethodInsn(INVOKESTATIC, BRIDGE, "status", "()Lorg/json/JSONObject;", false);
                status.visitMethodInsn(INVOKEVIRTUAL, "org/json/JSONObject", "toString", "()Ljava/lang/String;", false);
                status.visitMethodInsn(INVOKEINTERFACE, CALLBACK, "invoke", "(Ljava/lang/Object;)V", true);
                status.visitInsn(RETURN); status.visitMaxs(0, 0); status.visitEnd();
                MethodVisitor prepare = method("preparePrint", "(L" + CALLBACK + ";)V");
                prepare.visitVarInsn(ALOAD, 1);
                prepare.visitMethodInsn(INVOKESTATIC, BRIDGE, "preparePrint", "()Lorg/json/JSONObject;", false);
                prepare.visitMethodInsn(INVOKEVIRTUAL, "org/json/JSONObject", "toString", "()Ljava/lang/String;", false);
                prepare.visitMethodInsn(INVOKEINTERFACE, CALLBACK, "invoke", "(Ljava/lang/Object;)V", true);
                prepare.visitInsn(RETURN); prepare.visitMaxs(0, 0); prepare.visitEnd();
                super.visitEnd();
            }
        }, 0);
        classes.put(module, writer.toByteArray());
        // 原读线程提前丢状态包、按200ms拼接十六进制文本；必须在原始字节入口替换。
        replaceWithStatic(classes, PRINT + "$ReadThread.class", "run", "()V",
                "com/bike/pda/hardware/Qs608Receiver", "readLoop", false);
        // 解码后的统一出口过滤控制帧，杜绝假条码广播及假成功提示音。
        replaceWithStatic(classes, PRINT + "$2.class", "handleMessage", "(Landroid/os/Message;)V",
                BRIDGE, "onDecodedScan", false);
        replaceWithStatic(classes, PRINT + ".class", "send", "([B)V",
                "com/bike/pda/hardware/Qs608Transport", "send", true);
        replaceWithStatic(classes, PRINT + ".class", "printBitmap", "(IILandroid/graphics/Bitmap;ZZ)V",
                BRIDGE, "printBitmap", true);
        String handlerClass = PRINT + "$3.class";
        ClassWriter handlerWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(classes.get(handlerClass)).accept(new ClassVisitor(ASM9, handlerWriter) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("handleMessage")) return delegate;
                return new MethodVisitor(ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(ALOAD, 1);
                        super.visitMethodInsn(INVOKESTATIC, BRIDGE, "onPrinterMessage", "(Landroid/os/Message;)V", false);
                    }
                };
            }
        }, 0);
        classes.put(handlerClass, handlerWriter.toByteArray());
        Path classRoot = Paths.get(args[1]);
        try (var files = Files.walk(classRoot)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String relative = classRoot.relativize(path).toString().replace(File.separatorChar, '/');
                if (relative.startsWith("com/bike/") && relative.endsWith(".class"))
                    classes.put(relative, Files.readAllBytes(path));
            }
        }
        aar.put("classes.jar", zip(classes));
        // Android 11+ 只声明厂商 GPIO 服务所在包的可见性，不申请全量应用查询权限。
        String manifest = new String(aar.get("AndroidManifest.xml"), java.nio.charset.StandardCharsets.UTF_8);
        manifest = manifest.replace("</manifest>", "<queries><package android:name=\"com.android.settings\" /></queries></manifest>");
        aar.put("AndroidManifest.xml", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(Paths.get(args[2]), zip(aar));
        System.out.println("608 compatibility AAR generated; original vendor AAR preserved");
    }
    private static void replaceWithStatic(Map<String, byte[]> classes, String entry, String method, String descriptor,
            String targetOwner, String targetMethod, boolean isStatic) {
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        new ClassReader(classes.get(entry)).accept(new ClassVisitor(ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(method) || !desc.equals(descriptor))
                    return super.visitMethod(access, name, desc, signature, exceptions);
                matches[0]++;
                MethodVisitor replacement = super.visitMethod(access, name, desc, signature, exceptions);
                replacement.visitCode();
                int slot = isStatic ? 0 : 1;
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    replacement.visitVarInsn(argument.getOpcode(ILOAD), slot);
                    slot += argument.getSize();
                }
                replacement.visitMethodInsn(INVOKESTATIC, targetOwner, targetMethod, descriptor, false);
                replacement.visitInsn(RETURN);
                replacement.visitMaxs(0, 0); replacement.visitEnd();
                return null;
            }
        }, 0);
        if (matches[0] != 1) throw new IllegalStateException("Unexpected vendor method: " + entry + " / " + method);
        classes.put(entry, output.toByteArray());
    }
    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;)
                if (!entry.isDirectory()) result.put(entry.getName(), input.readAllBytes());
        }
        return result;
    }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey()); item.setTime(0);
                output.putNextEntry(item); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}

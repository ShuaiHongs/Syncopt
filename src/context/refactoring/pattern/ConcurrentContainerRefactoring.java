package context.refactoring.pattern;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.*;

public class ConcurrentContainerRefactoring extends RefactoringPattern {

    private static final String[] COLLECTION_METHODS = {
        "get", "put", "add", "remove", "contains", "containsKey", 
        "containsValue", "size", "clear", "addElement", "elementAt",
        "push", "pop", "offer", "poll", "isEmpty", "iterator"
    };

    @Override
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu) {
        String fieldName = findCollectionField(md);
        if (fieldName == null) return false;

        FieldDeclaration field = findFieldDeclaration(td, fieldName);
        if (field == null) {
            System.out.println("字段 " + fieldName + " 不在当前类，跳过并发容器替换");
            return false;
        }

        String oldType = field.getType().toString();
        String newType = mapToConcurrentType(oldType);
        if (newType == null) {
            System.out.println("字段 " + fieldName + " 类型 " + oldType + " 不是容器，跳过并发容器替换");
            return false;
        }

        AST ast = cu.getAST();
        VariableDeclarationFragment frag = (VariableDeclarationFragment) field.fragments().get(0);
        Expression oldInit = frag.getInitializer();

        // Set 类型特殊处理：字段类型改为 Set，初始化替换为 ConcurrentHashMap.newKeySet()
        if (newType.equals("__SET_METHOD_CALL__")) {
            // ---------- 修改字段类型 ----------
            Type originalType = field.getType();
            if (originalType.isParameterizedType()) {
                ParameterizedType paramType = (ParameterizedType) originalType;
                Type newRawType = ast.newSimpleType(ast.newSimpleName("Set"));
                ParameterizedType newParamType = ast.newParameterizedType(newRawType);
                for (Object arg : paramType.typeArguments()) {
                    newParamType.typeArguments().add(ASTNode.copySubtree(ast, (ASTNode) arg));
                }
                field.setType(newParamType);
            } else {
                field.setType(ast.newSimpleType(ast.newSimpleName("Set")));
            }

            // ---------- 修改初始化 ----------
            MethodInvocation invocation = ast.newMethodInvocation();
            invocation.setExpression(ast.newSimpleName("ConcurrentHashMap"));
            invocation.setName(ast.newSimpleName("newKeySet"));
            frag.setInitializer(invocation);   // 直接使用外部已有的 frag

            // ---------- 添加 import ----------
            addImportIfMissing(cu, "java.util.concurrent.ConcurrentHashMap");
        } else {
            // Map / List / Queue：直接替换类型和初始化类型
            field.setType(ast.newSimpleType(ast.newSimpleName(newType)));

            if (oldInit instanceof ClassInstanceCreation) {
                ClassInstanceCreation initExpr = (ClassInstanceCreation) oldInit;
                initExpr.setType(ast.newSimpleType(ast.newSimpleName(newType)));
            }
            addImportIfMissing(cu, "java.util.concurrent." + newType);
        }

        // 移除 synchronized 修饰符
        //md.modifiers().removeIf(m -> ((Modifier) m).isSynchronized());
     // 修复后
        md.modifiers().removeIf(m -> 
            m instanceof Modifier && ((Modifier) m).isSynchronized()
        );

        System.out.println("并发容器替换成功: " + td.getName() + "." + md.getName() + " 字段 " + fieldName + " -> " + newType);
        return true;
    }

    @Override
    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu) {
        boolean result = refactorMethod(td, md, cu);
        if (!result) return false;

        Block parentBlock = (Block) syncStmt.getParent();
        int idx = parentBlock.statements().indexOf(syncStmt);
        List<Statement> innerStmts = new ArrayList<>(syncStmt.getBody().statements());
        syncStmt.getBody().statements().clear();
        parentBlock.statements().remove(idx);
        parentBlock.statements().addAll(idx, innerStmts);
        return true;
    }

    // ===== 辅助方法 =====

    private String findCollectionField(MethodDeclaration md) {
        final String[] fieldName = {null};
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                String name = node.getName().getIdentifier();
                if (isCollectionMethod(name) && node.getExpression() instanceof SimpleName) {
                    fieldName[0] = ((SimpleName) node.getExpression()).getIdentifier();
                    return false;
                }
                return true;
            }
        });
        return fieldName[0];
    }

    private boolean isCollectionMethod(String name) {
        for (String m : COLLECTION_METHODS) {
            if (m.equals(name)) return true;
        }
        return false;
    }

    private FieldDeclaration findFieldDeclaration(TypeDeclaration td, String name) {
        for (FieldDeclaration fd : td.getFields()) {
            for (Object fragObj : fd.fragments()) {
                if (fragObj instanceof VariableDeclarationFragment) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) fragObj;
                    if (frag.getName().getIdentifier().equals(name)) {
                        return fd;
                    }
                }
            }
        }
        return null;
    }

    private String mapToConcurrentType(String oldType) {
        if (oldType.contains("HashMap") || oldType.contains("Map") || oldType.contains("TreeMap")) {
            return "ConcurrentHashMap";
        }
        if (oldType.contains("ArrayList") || oldType.contains("List") || oldType.contains("Vector")) {
            return "CopyOnWriteArrayList";
        }
        if (oldType.contains("HashSet") || oldType.contains("Set")) {
            // 特殊标记，上层会做方法调用替换
            return "__SET_METHOD_CALL__";
        }
        if (oldType.contains("Queue") || oldType.contains("Deque") || oldType.contains("LinkedBlockingQueue")) {
            return "ConcurrentLinkedDeque";
        }
        return null;
    }

    private void addImportIfMissing(CompilationUnit cu, String qualifiedName) {
        for (Object obj : cu.imports()) {
            if (obj instanceof ImportDeclaration) {
                ImportDeclaration id = (ImportDeclaration) obj;
                if (id.getName().getFullyQualifiedName().equals(qualifiedName)) {
                    return;
                }
            }
        }
        ImportDeclaration id = cu.getAST().newImportDeclaration();
        id.setName(cu.getAST().newName(qualifiedName));
        cu.imports().add(id);
    }
}
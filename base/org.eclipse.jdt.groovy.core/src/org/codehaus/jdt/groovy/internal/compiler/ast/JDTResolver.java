/*
 * Copyright 2009-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.jdt.groovy.internal.compiler.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.ResolveVisitor;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.jdt.groovy.internal.compiler.ast.GroovyParser.GrapeAwareGroovyClassLoader;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.groovy.core.util.ReflectionUtils;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

/**
 * An extension to the standard groovy ResolveVisitor that can ask JDT for types when groovy cannot find them. A groovy project in
 * Eclipse is typically configured with very limited knowledge of its dependencies so most lookups are through JDT.
 *
 * Resolver lifecycle:<br>
 * The JDTResolver is created at the same time as the (Groovy) CompilationUnit. The CompilationUnit knows about all the code that is
 * to be compiled together. The resolver maintains a cache from Binding to JDTClassNode and the cache contents have the same
 * lifetime as the JDTResolver. The resolver does type lookups through the currently active scope - the active scope is set when the
 * method 'commencingResolution()' is called. This is called by the superclass (ResolveVisitor) when it is about to start resolving
 * every reference in a type.
 *
 * @author Andy Clement
 */
public class JDTResolver extends ResolveVisitor {

    private static final boolean DEBUG = false;

    /** Any type name that is equal to or shorter than this could be a primitive type. */
    private static final int BOOLEAN_LENGTH = "boolean".length();

    /** Arbitrary selection of common types. */
    private static final Map<String, ClassNode> COMMON_TYPES;
    static {
        Map<String, ClassNode> commonTypes = new HashMap<String, ClassNode>();

        commonTypes.put("boolean", ClassHelper.boolean_TYPE);
        commonTypes.put("byte", ClassHelper.byte_TYPE);
        commonTypes.put("char", ClassHelper.char_TYPE);
        commonTypes.put("double", ClassHelper.double_TYPE);
        commonTypes.put("float", ClassHelper.float_TYPE);
        commonTypes.put("int", ClassHelper.int_TYPE);
        commonTypes.put("long", ClassHelper.long_TYPE);
        commonTypes.put("short", ClassHelper.short_TYPE);
        commonTypes.put("void", ClassHelper.VOID_TYPE);

        commonTypes.put("java.lang.Boolean", ClassHelper.Boolean_TYPE);
        commonTypes.put("java.lang.Byte", ClassHelper.Byte_TYPE);
        commonTypes.put("java.lang.Character", ClassHelper.Character_TYPE);
        commonTypes.put("java.lang.Double", ClassHelper.Double_TYPE);
        commonTypes.put("java.lang.Float", ClassHelper.Float_TYPE);
        commonTypes.put("java.lang.Integer", ClassHelper.Integer_TYPE);
        commonTypes.put("java.lang.Long", ClassHelper.Long_TYPE);
        commonTypes.put("java.lang.Short", ClassHelper.Short_TYPE);
        commonTypes.put("java.lang.Void", ClassHelper.void_WRAPPER_TYPE);

        commonTypes.put("java.lang.Object", ClassHelper.OBJECT_TYPE);
        commonTypes.put("java.lang.String", ClassHelper.STRING_TYPE);

        COMMON_TYPES = Collections.unmodifiableMap(commonTypes);
    }

    private void log(String string) {
        System.err.printf("JDTResolver@%x[%d]: %s%n", System.identityHashCode(this), Thread.currentThread().getId(), string);
    }
    private void log(String string, ClassNode type, boolean foundit) {
        log(string + " " + type.getName() + "? " + foundit);
    }

    // allow test cases to quiz a resolver
    public static boolean recordInstances = false;
    public static List<JDTResolver> instances = null;
    public static JDTClassNode getCachedNode(String name) {
        for (JDTResolver instance : instances) {
            JDTClassNode node = getCachedNode(instance, name);
            if (node != null) return node;
        }
        return null;
    }
    public static JDTClassNode getCachedNode(JDTResolver instance, String name) {
        for (JDTClassNode nodeFromCache : instance.nodeCache.values()) {
            if (name.equals(String.valueOf(nodeFromCache.jdtBinding.readableName()))) {
                return nodeFromCache;
            }
        }
        return null;
    }

    public GroovyCompilationUnitScope getScope() {
        return activeScope;
    }

    // Type references are resolved through the 'activeScope'. This ensures visibility rules are obeyed - just because a
    // type exists does not mean it is visible to some other type and scope lookups verify this.
    protected GroovyCompilationUnitScope activeScope = null;

    // map of scopes in which resolution can happen
    private Map<ClassNode, GroovyTypeDeclaration> scopes = new HashMap<ClassNode, GroovyTypeDeclaration>();

    // By recording what is currently in progress in terms of creation, we avoid recursive problems (like Enum<E extends Enum<E>>)
    private Map<TypeBinding, JDTClassNode> inProgress = new IdentityHashMap<TypeBinding, JDTClassNode>();

    // Cache from bindings to JDTClassNodes to avoid unnecessary JDTClassNode creation
    private Map<TypeBinding, JDTClassNode> nodeCache = new IdentityHashMap<TypeBinding, JDTClassNode>();

    private Set<ClassNode> resolvedClassNodes = new HashSet<ClassNode>();

    /**
     * Records the type names that aren't resolvable for the current resolution
     * (cleared in finishedResolution()). This means we won't constantly attempt
     * to lookup something that is not found through the same routes repeatedly.
     */
    private Set<String> unresolvables = new HashSet<String>();

    //--------------------------------------------------------------------------

    public JDTResolver(CompilationUnit groovyCompilationUnit) {
        super(groovyCompilationUnit);
        if (recordInstances) {
            if (instances == null) {
                instances = new ArrayList<JDTResolver>();
            }
            instances.add(this);
        }
    }

    public void cleanUp() {
        inProgress.clear();
        //nodeCache.clear();
    }

    public ClassNode resolve(String name) {
        if (name.charAt(0) == 'j' || name.length() <= BOOLEAN_LENGTH) {
            ClassNode commonRedirect = COMMON_TYPES.get(name);
            if (commonRedirect != null) {
                return commonRedirect;
            }
        }

        if (unresolvables.contains(name)) {
            return ClassHelper.DYNAMIC_TYPE;
        }

        ClassNode type = ClassHelper.makeWithoutCaching(name);
        if (super.resolve(type, true, true, true)) {
            return type.redirect();
        } else {
            unresolvables.add(name);
            return ClassHelper.DYNAMIC_TYPE;
        }
    }

    @Override
    protected boolean resolve(ClassNode type, boolean testModuleImports, boolean testDefaultImports, boolean testStaticInnerClasses) {
        String name = type.getName();
        if (name.charAt(0) == 'j' || name.length() <= BOOLEAN_LENGTH) {
            ClassNode commonRedirect = COMMON_TYPES.get(name);
            if (commonRedirect != null) {
                type.setRedirect(commonRedirect);
                return true;
            }
        }

        if (unresolvables.contains(name)) {
            return false;
        }

        boolean b = super.resolve(type, testModuleImports, testDefaultImports, testStaticInnerClasses);
        if (!b) {
            unresolvables.add(name);
        }
        return b;
    }

    @Override
    protected boolean resolveFromModule(ClassNode type, boolean testModuleImports) {
        boolean foundit = super.resolveFromModule(type, testModuleImports);
        recordDependency(type.getName());
        if (DEBUG) {
            log("resolveFromModule", type, foundit);
        }
        if (foundit) {
            if (type.redirect() instanceof JDTClassNode && ((JDTClassNode) type.redirect()).getJdtBinding().hasRestrictedAccess()) {
                TypeBinding binding = ((JDTClassNode) type.redirect()).getJdtBinding();
                AccessRestriction restriction = activeScope.environment().getAccessRestriction(binding.erasure());
                if (restriction != null) {
                    SingleTypeReference ref = new SingleTypeReference(type.getNameWithoutPackage().toCharArray(), ((long) type.getStart() << 32 | (long) type.getEnd() - 1));
                    activeScope.problemReporter().forbiddenReference(binding, ref, restriction.classpathEntryType, restriction.classpathEntryName, restriction.getProblemId());
                }
            }
        }
        return foundit;
    }

    @Override
    protected boolean resolveFromCompileUnit(ClassNode type) {
        boolean foundit = super.resolveFromCompileUnit(type);
        recordDependency(type.getName());
        if (DEBUG) {
            log("resolveFromCompileUnit", type, foundit);
        }
        if (foundit) {
            return true;
        }
        if (activeScope != null) {
            // Ask JDT for a source file, visible from this scope
            ClassNode node = activeScope.lookupClassNodeForSource(type.getName(), this);
            if (DEBUG) {
                log("resolveFromCompileUnit (jdt) ", type, node != null);
            }
            if (node != null) {
                type.setRedirect(node);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean resolveFromDefaultImports(ClassNode type, boolean testDefaultImports) {
        boolean foundit = super.resolveFromDefaultImports(type, testDefaultImports);
        recordDependency(type.getName());
        if (DEBUG) {
            log("resolveFromDefaultImports", type, foundit);
        }
        return foundit;
    }

    @Override
    protected boolean resolveFromStaticInnerClasses(ClassNode type, boolean testStaticInnerClasses) {
        boolean foundit = super.resolveFromStaticInnerClasses(type, testStaticInnerClasses);
        recordDependency(type.getName());
        if (DEBUG) {
            log("resolveFromStaticInnerClasses", type, foundit);
        }
        return foundit;
    }

    @Override
    protected boolean resolveToOuter(ClassNode type) {
        ClassNode node;
        if (activeScope != null) {
            node = activeScope.lookupClassNodeForBinary(type.getName(), this);
            if (DEBUG) {
                log("resolveToOuter (jdt)", type, node != null);
            }
            if (node != null) {
                type.setRedirect(node);
                return true;
            }
        }
        // Rudimentary grab support - if the compilation unit has our special classloader and a
        // grab has occurred, try and find the class through it
        GroovyClassLoader loader = compilationUnit.getClassLoader();
        if (loader instanceof GrapeAwareGroovyClassLoader) {
            GrapeAwareGroovyClassLoader gagcl = (GrapeAwareGroovyClassLoader) loader;
            if (gagcl.grabbed) {
                Class<?> cls;
                try {
                    cls = loader.loadClass(type.getName(), false, true);
                } catch (ClassNotFoundException cnfe) {
                    return false;
                } catch (CompilationFailedException cfe) {
                    return false;
                }
                if (cls == null) {
                    return false;
                }
                node = ClassHelper.make(cls);
                type.setRedirect(node);
                return true;
            }
        }
        return false;
    }

    // avoiding an inner resolve is dangerous
    // leave a back door here to turn it back on
    // if no one complains, then safe to remove
    private static boolean doInnerResolve = Boolean.getBoolean("greclipse.doInnerResolve");

    @Override
    protected boolean resolveToInner(ClassNode type) {
        if (doInnerResolve) {
            return super.resolveToInner(type);
        }
        // inner classes are resolved by JDT, so
        // if we get here then the inner class does not exist
        return false;
    }

    @Override
    protected boolean resolveToInnerEnum(ClassNode type) {
        if (doInnerResolve) {
            return super.resolveToInnerEnum(type);
        }
        // inner classes are resolved by JDT, so
        // if we get here then the inner class does not exist
        return false;
    }

    /**
     * Converts a JDT TypeBinding to a Groovy ClassNode.
     */
    protected ClassNode convertToClassNode(TypeBinding jdtBinding) {
        ClassNode existingNode = checkForExisting(jdtBinding);
        if (existingNode != null) {
            if (DEBUG) {
                log("Using cached ClassNode for binding " + toString(jdtBinding));
            }
            return existingNode;
        }
        if (DEBUG) {
            log("Building new JDTClassNode for binding " + toString(jdtBinding));
        }
        return createJDTClassNode(jdtBinding);
    }

    private ClassNode checkForExisting(TypeBinding jdtBinding) {
        if (jdtBinding.id > TypeIds.T_undefined && (jdtBinding.id <= TypeIds.T_JavaLangString ||
                (jdtBinding.id <= TypeIds.T_JavaLangVoid && jdtBinding.id >= TypeIds.T_JavaLangByte))) {
            ClassNode existing = COMMON_TYPES.get(String.valueOf(jdtBinding.readableName()));
            if (existing != null) {
                return existing;
            }
        }

        JDTClassNode node = inProgress.get(jdtBinding);
        if (node == null) {
            node = nodeCache.get(jdtBinding);
        }
        if (node != null) {
            assert Arrays.equals(jdtBinding.readableName(), node.jdtBinding.readableName());
        }
        return node;
    }

    /**
     * Creates a Groovy ClassNode that represents the JDT TypeBinding. Steps
     * include building the basic structure, marking node as 'in progress' and
     * continuing with initialization. This allows self-referential generics.
     *
     * @param jdtBinding the JDT binding for which to create a ClassNode
     */
    private ClassNode createJDTClassNode(TypeBinding jdtBinding) {
        JDTClassNodeBuilder cnb = new JDTClassNodeBuilder(this);
        ClassNode classNode = cnb.configureType(jdtBinding);
        if (classNode instanceof JDTClassNode) {
            final JDTClassNode jdtNode = (JDTClassNode) classNode;
            assert !inProgress.containsKey(jdtBinding);
            inProgress.put(jdtBinding, jdtNode);

            // fix up generics for BinaryTypeBinding
            jdtNode.setupGenerics();

            assert nodeCache.get(jdtBinding) == null : "not unique";
            nodeCache.put(jdtBinding, jdtNode);
            inProgress.remove(jdtBinding);
        }
        return classNode;
    }

    /**
     * Called when a resolvevisitor is commencing resolution for a type - allows us to setup the JDTResolver to point at the right
     * scope for resolutionification. If not able to find a scope, that is a serious problem!
     */
    @Override
    protected boolean commencingResolution() {
        GroovyTypeDeclaration gtDeclaration = scopes.get(currentClass);
        if (gtDeclaration == null) {
            if (resolvedClassNodes.contains(currentClass)) {
                // already resolved!
                return false;
            }
            throw new GroovyEclipseBug("commencingResolution failed: no declaration found for class " + currentClass);
        }
        activeScope = null;
        if (gtDeclaration.scope == null) {
            // The scope may be null if there were errors in the code - let's not freak out the user here
            if (gtDeclaration.hasErrors()) {
                return false;
            }
            throw new GroovyEclipseBug("commencingResolution failed: declaration found, but unexpectedly found no scope for " + currentClass.getName());
        }
        activeScope = (GroovyCompilationUnitScope) gtDeclaration.scope.compilationUnitScope();
        if (DEBUG) {
            log("commencing resolution for " + currentClass.getName());
        }
        return true;
    }

    @Override
    protected void finishedResolution() {
        resolvedClassNodes.add(currentClass);
        scopes.remove(currentClass);
        unresolvables.clear();
    }

    /**
     * When recorded, the jdt resolver will be able to (later on) navigate from the classnode back to the JDT scope that should be
     * used.
     */
    public void record(GroovyTypeDeclaration gtDeclaration) {
        // FIXASC can the relationship here from classNode to scope be better preserved to remove the need for this map?
        scopes.put(gtDeclaration.getClassNode(), gtDeclaration);
        if (gtDeclaration.memberTypes != null) {
            TypeDeclaration[] members = gtDeclaration.memberTypes;
            for (int m = 0; m < members.length; m++) {
                record((GroovyTypeDeclaration) members[m]);
            }
        }
        GroovyTypeDeclaration[] anonymousTypes = gtDeclaration.getAnonymousTypes();
        if (anonymousTypes != null) {
            for (int m = 0; m < anonymousTypes.length; m++) {
                record(anonymousTypes[m]);
            }
        }
    }

    // FIXASC callers could check if it is a 'funky' type before always recording a depedency
    // by 'funky' I mean that the type was constructed just to try something (org.foo.bar.java$lang$Wibble doesn't want recording!)
    private void recordDependency(String typename) {
        if (activeScope != null) {
            if (typename.indexOf('.') != -1) {
                activeScope.recordQualifiedReference(CharOperation.splitOn('.', typename.toCharArray()));
            } else {
                activeScope.recordSimpleReference(typename.toCharArray());
            }
        }
    }

    public void startResolving(ClassNode node, SourceUnit source) {
        try {
            super.startResolving(node, source);
            unresolvables.clear();
        } catch (AbortResolutionException are) {
            // Can occur if there are other problems with the node (syntax errors) - so don't try resolving it
        }
    }

    private static String toString(TypeBinding jdtBinding) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(jdtBinding.readableName());
        buffer.append('(').append(jdtBinding.id).append(')');
        try {
            Object lookup = ReflectionUtils.throwableGetPrivateField(jdtBinding.getClass(), "environment", jdtBinding);
            buffer.append("[from lookup ").append(Integer.toHexString(System.identityHashCode(lookup))).append(']');
        } catch (Throwable t) {
            // not available
        }
        return buffer.toString();
    }
}

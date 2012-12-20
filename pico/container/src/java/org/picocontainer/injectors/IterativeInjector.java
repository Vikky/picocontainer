package org.picocontainer.injectors;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.picocontainer.ComponentMonitor;
import org.picocontainer.NameBinding;
import org.picocontainer.Parameter;
import org.picocontainer.PicoCompositionException;
import org.picocontainer.PicoContainer;
import org.picocontainer.annotations.Bind;

import com.thoughtworks.paranamer.AdaptiveParanamer;
import com.thoughtworks.paranamer.AnnotationParanamer;
import com.thoughtworks.paranamer.CachingParanamer;
import com.thoughtworks.paranamer.Paranamer;

/**
 * Injection will happen iteratively after component instantiation
 */
@SuppressWarnings("serial")
public abstract class IterativeInjector<T> extends AbstractInjector<T> {

    private static final Object[] NONE = new Object[0];
    
    private transient ThreadLocalCyclicDependencyGuard<T> instantiationGuard;
    protected volatile transient List<AccessibleObject> injectionMembers;
    protected transient Type[] injectionTypes;
    protected transient Annotation[] bindings;

    private transient Paranamer paranamer;
    private volatile transient boolean initialized;
    
    
    
    /**
     * Constructs a IterativeInjector
     *
     * @param key            the search key for this implementation
     * @param impl the concrete implementation
     * @param monitor                 the component monitor used by this addAdapter
     * @param useNames                use argument names when looking up dependencies
     * @param parameters              the parameters to use for the initialization
     * @throws org.picocontainer.injectors.AbstractInjector.NotConcreteRegistrationException
     *                              if the implementation is not a concrete class.
     * @throws NullPointerException if one of the parameters is <code>null</code>
     */
    public IterativeInjector(final Object key, final Class<?> impl, ComponentMonitor monitor, boolean useNames,
                             Parameter... parameters) throws  NotConcreteRegistrationException {
        super(key, impl, monitor, useNames, parameters);
    }

    protected Constructor<?> getConstructor()  {
        Object retVal = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    return getComponentImplementation().getConstructor((Class[])null);
                } catch (NoSuchMethodException e) {
                    return new PicoCompositionException(e);
                } catch (SecurityException e) {
                    return new PicoCompositionException(e);
                }
            }
        });
        if (retVal instanceof Constructor) {
            return (Constructor<?>) retVal;
        } else {
            throw (PicoCompositionException) retVal;
        }
    }
    
    

    /**
     * Paired parameter/accessible object
     * @author Michael Rimov
     *
     */
    public static class ParameterToAccessibleObjectPair {
		private final AccessibleObject accessibleObject;
    	
    	private final Parameter parameter;
    	
    	
    	/**
    	 * 
    	 * @param accessibleObject
    	 * @param parameter set to null if there was no resolution for this accessible object.
    	 */
    	public ParameterToAccessibleObjectPair(AccessibleObject accessibleObject, Parameter parameter) {
			super();
			this.accessibleObject = accessibleObject;
			this.parameter = parameter;
		}

		public AccessibleObject getAccessibleObject() {
			return accessibleObject;
		}

		public Parameter getParameter() {
			return parameter;
		}

    	public boolean isResolved() {
    		return parameter != null;
    	}
    	
    }
    

    private ParameterToAccessibleObjectPair[] getMatchingParameterListForMembers(PicoContainer container) throws PicoCompositionException {
        if (initialized == false) {
        	synchronized(this) {
        		if (initialized == false) {
        			initializeInjectionMembersAndTypeLists();
        		}
        	}
        }

        final List<Object> matchingParameterList = new ArrayList<Object>(Collections.nCopies(injectionMembers.size(), null));

        final Parameter[] currentParameters = parameters != null ? parameters : createDefaultParameters(injectionTypes.length);
        validateParametersAreEitherAllNoTargetOrAllTarget(currentParameters);
        final Set<Integer> nonMatchingParameterPositions = matchParameters(container, matchingParameterList, currentParameters);

        final Set<Type> unsatisfiableDependencyTypes = new HashSet<Type>();
        final List<AccessibleObject> unsatisfiableDependencyMembers = new ArrayList<AccessibleObject>();
        
        for (int i = 0; i < matchingParameterList.size(); i++) {
        	ParameterToAccessibleObjectPair param = (ParameterToAccessibleObjectPair)matchingParameterList.get(i);
            if (param == null ||  !param.isResolved()) {
                unsatisfiableDependencyTypes.add(injectionTypes[i]);
                unsatisfiableDependencyMembers.add(injectionMembers.get(i));
            }
        }
        if (unsatisfiableDependencyTypes.size() > 0) {
        	unsatisfiedDependencies(container, unsatisfiableDependencyTypes, unsatisfiableDependencyMembers);
        } else if (nonMatchingParameterPositions.size() > 0) {
            throw new PicoCompositionException("Following parameters do not match any of the injectionMembers for " + getComponentImplementation() + ": " + nonMatchingParameterPositions.toString());
        }
        return matchingParameterList.toArray(new ParameterToAccessibleObjectPair[matchingParameterList.size()]);
    }

        
    protected void validateParametersAreEitherAllNoTargetOrAllTarget(final Parameter[] currentParameters) {
		boolean pass = true;
		boolean firstHasTarget = false;
		if (currentParameters.length > 0) {
			firstHasTarget = (currentParameters[0].getTargetName() != null);
		}
		
		for (Parameter eachParameter : currentParameters) {
			pass = !(firstHasTarget ^ (eachParameter.getTargetName() != null));
		}
		
	}

	/**
     * Returns a set of integers that point to where in the Parameter array unmatched parameters exist.
     * @param container
     * @param matchingParameterList
     * @param currentParameters {@link org.picocontainer.Parameter} for the current object being instantiated.
     * @return set of integers pointing to the index in the parameter array things went awry.
     */
    private Set<Integer> matchParameters(PicoContainer container, List<Object> matchingParameterList, Parameter... currentParameters) {
    	
        Set<Integer> unmatchedParameters = new HashSet<Integer>();
        for (int i = 0; i < currentParameters.length; i++) {
            if (!matchParameter(container, matchingParameterList, currentParameters[i])) {
                unmatchedParameters.add(i);
            }
        }
        
        return unmatchedParameters;
    }

    private boolean matchParameter(PicoContainer container, List<Object> matchingParameterList, Parameter parameter) {
        for (int j = 0; j < injectionTypes.length; j++) {
            Object o = matchingParameterList.get(j);
            AccessibleObject targetInjectionMember = getTargetInjectionMember(injectionMembers, j, parameter);
            Parameter paramToUse = getParameterToUseForObject(targetInjectionMember, parameter);
            try {
                if (o == null
                        && paramToUse.resolve(container, this, null, injectionTypes[j],
                                                   makeParameterNameImpl(targetInjectionMember),
                                                   useNames(), bindings[j]).isResolved()) {
                    matchingParameterList.set(j, new ParameterToAccessibleObjectPair(targetInjectionMember, paramToUse));
                    return true;
                }
            } catch (AmbiguousComponentResolutionException e) {
                e.setComponent(getComponentImplementation());
                e.setMember(injectionMembers.get(j));
                throw e;
            }
        }
        return false;
    }

    /**
     * Allow swapping of parameters by derived classes.  Default implementation always returns the current parameter.
     * @param targetInjectionMember
     * @param currentParameter
     * @return
     */
    protected Parameter getParameterToUseForObject(AccessibleObject targetInjectionMember, Parameter currentParameter) {
    	return currentParameter;
	}

	abstract protected boolean isAccessibleObjectEqualToParameterTarget(AccessibleObject testObject, Parameter currentParameter);
    
	private AccessibleObject getTargetInjectionMember(List<AccessibleObject> injectionMembers, int currentIndex,
			Parameter parameter) {
		
		if (parameter.getTargetName() == null) {
			return injectionMembers.get(currentIndex);
		}
		
		for (AccessibleObject eachObject : injectionMembers) {
			if (isAccessibleObjectEqualToParameterTarget(eachObject, parameter)) {
				return eachObject;
			}
		}
		
		throw new PicoCompositionException("There was no matching target field/method for target name " + parameter.getTargetName());
	}

	protected NameBinding makeParameterNameImpl(AccessibleObject member) {
		if (member == null) {
			throw new NullPointerException("member");
		}
		
        if (paranamer == null) {
            paranamer = new CachingParanamer(new AnnotationParanamer(new AdaptiveParanamer()));
        }
        return new ParameterNameBinding(paranamer,  member, 0);
    }

    protected abstract void unsatisfiedDependencies(PicoContainer container, Set<Type> unsatisfiableDependencyTypes, List<AccessibleObject> unsatisfiableDependencyMembers);

    public T getComponentInstance(final PicoContainer container, final Type into) throws PicoCompositionException {
        final Constructor<?> constructor = getConstructor();
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard<T>() {
                public T run(Object instance) {
                    final ParameterToAccessibleObjectPair[] matchingParameters = getMatchingParameterListForMembers(guardedContainer);
                    Object componentInstance = makeInstance(container, constructor, currentMonitor());
                    return  decorateComponentInstance(matchingParameters, currentMonitor(), componentInstance, container, guardedContainer, into);
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        return instantiationGuard.observe(getComponentImplementation(), null);
    }

    private T decorateComponentInstance(ParameterToAccessibleObjectPair[] matchingParameters, ComponentMonitor monitor, Object componentInstance, PicoContainer container, PicoContainer guardedContainer, Type into) {
        AccessibleObject member = null;
        Object injected[] = new Object[injectionMembers.size()];
        Object lastReturn = null;
        try {
            for (int i = 0; i < matchingParameters.length; i++) {
            	if (matchingParameters[i] != null) {
            		member = matchingParameters[i].getAccessibleObject();
            	}
            	
                if (matchingParameters[i] != null && matchingParameters[i].isResolved()) {
                    Object toInject = matchingParameters[i].getParameter().resolve(guardedContainer, this, null, injectionTypes[i],
                                                                            makeParameterNameImpl(injectionMembers.get(i)),
                                                                            useNames(), bindings[i]).resolveInstance(into);
                    Object rv = monitor.invoking(container, this, (Member) member, componentInstance, new Object[] {toInject});
                    if (rv == ComponentMonitor.KEEP) {
                        long str = System.currentTimeMillis();
                        lastReturn = injectIntoMember(member, componentInstance, toInject);
                        monitor.invoked(container, this, (Member) member, componentInstance, System.currentTimeMillis() - str, lastReturn, new Object[] {toInject});
                    } else {
                        lastReturn = rv;
                    }
                    injected[i] = toInject;
                }
            }
            return (T) memberInvocationReturn(lastReturn, member, componentInstance);
        } catch (InvocationTargetException e) {
            return caughtInvocationTargetException(monitor, (Member) member, componentInstance, e);
        } catch (IllegalAccessException e) {
            return caughtIllegalAccessException(monitor, (Member) member, componentInstance, e);
        }
    }

    protected abstract Object memberInvocationReturn(Object lastReturn, AccessibleObject member, Object instance);

    private Object makeInstance(PicoContainer container, Constructor constructor, ComponentMonitor monitor) {
        long startTime = System.currentTimeMillis();
        Constructor constructorToUse = monitor.instantiating(container,
                                                                      IterativeInjector.this, constructor);
        Object componentInstance;
        try {
            componentInstance = newInstance(constructorToUse, null);
        } catch (InvocationTargetException e) {
            monitor.instantiationFailed(container, IterativeInjector.this, constructorToUse, e);
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException)e.getTargetException();
            } else if (e.getTargetException() instanceof Error) {
                throw (Error)e.getTargetException();
            }
            throw new PicoCompositionException(e.getTargetException());
        } catch (InstantiationException e) {
            return caughtInstantiationException(monitor, constructor, e, container);
        } catch (IllegalAccessException e) {
            return caughtIllegalAccessException(monitor, constructor, e, container);
        }
        monitor.instantiated(container,
                                      IterativeInjector.this,
                                      constructorToUse,
                                      componentInstance,
                                      NONE,
                                      System.currentTimeMillis() - startTime);
        return componentInstance;
    }

    @Override
    public Object decorateComponentInstance(final PicoContainer container, final Type into, final T instance) {
        if (instantiationGuard == null) {
            instantiationGuard = new ThreadLocalCyclicDependencyGuard<T>() {
                public T run(final Object inst) {
                    final ParameterToAccessibleObjectPair[] matchingParameters = getMatchingParameterListForMembers(guardedContainer);
                    return decorateComponentInstance(matchingParameters, currentMonitor(), inst, container, guardedContainer, into);
                }
            };
        }
        instantiationGuard.setGuardedContainer(container);
        return instantiationGuard.observe(getComponentImplementation(), instance);
    }

    protected abstract Object injectIntoMember(AccessibleObject member, Object componentInstance, Object toInject) throws IllegalAccessException, InvocationTargetException;

    @Override
    public void verify(final PicoContainer container) throws PicoCompositionException {
        if (verifyingGuard == null) {
            verifyingGuard = new ThreadLocalCyclicDependencyGuard<T>() {
                public T run(Object inst) {
                    final ParameterToAccessibleObjectPair[] currentParameters = getMatchingParameterListForMembers(guardedContainer);
                    for (int i = 0; i < currentParameters.length; i++) {
                        currentParameters[i].getParameter().verify(container, IterativeInjector.this, injectionTypes[i],
                                                    makeParameterNameImpl(currentParameters[i].getAccessibleObject()), useNames(), bindings[i]);
                    }
                    return null;
                }
            };
        }
        verifyingGuard.setGuardedContainer(container);
        verifyingGuard.observe(getComponentImplementation(), null);
    }

    protected void initializeInjectionMembersAndTypeLists() {
        injectionMembers = new ArrayList<AccessibleObject>();
        Set<String> injectionMemberNames = new HashSet<String>();
        List<Annotation> bingingIds = new ArrayList<Annotation>();
        final List<String> nameList = new ArrayList<String>();
        final List<Type> typeList = new ArrayList<Type>();
        final Method[] methods = getMethods();
        for (final Method method : methods) {
            final Type[] parameterTypes = method.getGenericParameterTypes();
            fixGenericParameterTypes(method, parameterTypes);

            String methodSignature = crudeMethodSignature(method);            
            
            // We're only interested if there is only one parameter and the method name is bean-style.
            if (parameterTypes.length == 1) {
                boolean isInjector = isInjectorMethod(method);
                // ... and the method name is bean-style.
                // We're also not interested in dupes from parent classes (not all JDK impls)
                if (isInjector && !injectionMemberNames.contains(methodSignature)) {
                    injectionMembers.add(method);
                    injectionMemberNames.add(methodSignature);
                    nameList.add(getName(method));
                    typeList.add(box(parameterTypes[0]));
                    bingingIds.add(getBindings(method, 0));
                }
            }
        }
        injectionTypes = typeList.toArray(new Type[0]);
        bindings = bingingIds.toArray(new Annotation[0]);
        initialized = true;
    }
    
    public static String crudeMethodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getReturnType().getName());
        sb.append(method.getName());
        for (Class<?> pType : method.getParameterTypes()) {
            sb.append(pType.getName());
        }
        return sb.toString();
    }
    

    protected String getName(Method method) {
        return null;
    }

    private void fixGenericParameterTypes(Method method, Type[] parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Type parameterType = parameterTypes[i];
            if (parameterType instanceof TypeVariable) {
                parameterTypes[i] = method.getParameterTypes()[i];
            }
        }
    }


    private Annotation getBindings(Method method, int i) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        if (parameterAnnotations.length >= i +1) {
            Annotation[] o = parameterAnnotations[i];
            for (Annotation annotation : o) {
                if (annotation.annotationType().getAnnotation(Bind.class) != null) {
                    return annotation;
                }
            }
            return null;

        }
        //TODO - what's this ?
        if (parameterAnnotations != null) {
            //return ((Bind) method.getAnnotation(Bind.class)).id();
        }
        return null;

    }

    protected boolean isInjectorMethod(Method method) {
        return false;
    }

    private Method[] getMethods() {
        return  AccessController.doPrivileged(new PrivilegedAction<Method[]>() {
            public Method[] run() {
                return getComponentImplementation().getMethods();
            }
        });
    }


}

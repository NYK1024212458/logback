/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Contributors:  Georg Lundesgaard
package ch.qos.logback.core.joran.spi;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.MethodDescriptor;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.joran.action.IADataForComplexProperty;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.util.AggregationType;
import ch.qos.logback.core.util.PropertySetterException;

/**
 * General purpose Object property setter. Clients repeatedly invokes
 * {@link #setProperty setProperty(name,value)} in order to invoke setters on
 * the Object specified in the constructor. This class relies on the JavaBeans
 * {@link Introspector} to analyze the given Object Class using reflection.
 * 
 * <p> Usage:
 * 
 * <pre>
 * PropertySetter ps = new PropertySetter(anObject);
 * ps.set(&quot;name&quot;, &quot;Joe&quot;);
 * ps.set(&quot;age&quot;, &quot;32&quot;);
 * ps.set(&quot;isMale&quot;, &quot;true&quot;);
 * </pre>
 * 
 * will cause the invocations anObject.setName("Joe"), anObject.setAge(32), and
 * setMale(true) if such methods exist with those signatures. Otherwise an
 * {@link IntrospectionException} are thrown.
 * 
 * @author Anders Kristensen
 * @author Ceki Gulcu
 */
public class PropertySetter extends ContextAwareBase {
  private static final Class[] STING_CLASS_PARAMETER = new Class[] { String.class };

  protected Object obj;
  protected Class objClass;
  protected PropertyDescriptor[] propertyDescriptors;
  protected MethodDescriptor[] methodDescriptors;

  /**
   * Create a new PropertySetter for the specified Object. This is done in
   * preparation for invoking {@link #setProperty} one or more times.
   * 
   * @param obj
   *                the object for which to set properties
   */
  public PropertySetter(Object obj) {
    this.obj = obj;
    this.objClass = obj.getClass();
  }

  /**
   * Uses JavaBeans {@link Introspector} to computer setters of object to be
   * configured.
   */
  protected void introspect() {
    try {
      BeanInfo bi = Introspector.getBeanInfo(obj.getClass());
      propertyDescriptors = bi.getPropertyDescriptors();
      methodDescriptors = bi.getMethodDescriptors();
    } catch (IntrospectionException ex) {
      addError("Failed to introspect " + obj + ": " + ex.getMessage());
      propertyDescriptors = new PropertyDescriptor[0];
      methodDescriptors = new MethodDescriptor[0];
    }
  }

  /**
   * Set a property on this PropertySetter's Object. If successful, this method
   * will invoke a setter method on the underlying Object. The setter is the one
   * for the specified property name and the value is determined partly from the
   * setter argument type and partly from the value specified in the call to
   * this method.
   * 
   * <p> If the setter expects a String no conversion is necessary. If it
   * expects an int, then an attempt is made to convert 'value' to an int using
   * new Integer(value). If the setter expects a boolean, the conversion is by
   * new Boolean(value).
   * 
   * @param name
   *                name of the property
   * @param value
   *                String value of the property
   */
  public void setProperty(String name, String value) {
    if (value == null) {
      return;
    }

    name = Introspector.decapitalize(name);

    PropertyDescriptor prop = getPropertyDescriptor(name);

    if (prop == null) {
      addWarn("No such property [" + name + "] in " + objClass.getName() + ".");
    } else {
      try {
        setProperty(prop, name, value);
      } catch (PropertySetterException ex) {
        addWarn("Failed to set property [" + name + "] to value \"" + value
            + "\". ", ex);
      }
    }
  }

  /**
   * Set the named property given a {@link PropertyDescriptor}.
   * 
   * @param prop
   *                A PropertyDescriptor describing the characteristics of the
   *                property to set.
   * @param name
   *                The named of the property to set.
   * @param value
   *                The value of the property.
   */
  public void setProperty(PropertyDescriptor prop, String name, String value)
      throws PropertySetterException {
    Method setter = prop.getWriteMethod();

    if (setter == null) {
      throw new PropertySetterException("No setter for property [" + name
          + "].");
    }

    Class[] paramTypes = setter.getParameterTypes();

    if (paramTypes.length != 1) {
      throw new PropertySetterException("#params for setter != 1");
    }

    Object arg;

    try {
      arg = convertArg(value, paramTypes[0]);
    } catch (Throwable t) {
      throw new PropertySetterException("Conversion to type [" + paramTypes[0]
          + "] failed. ", t);
    }

    if (arg == null) {
      throw new PropertySetterException("Conversion to type [" + paramTypes[0]
          + "] failed.");
    }
    try {
      setter.invoke(obj, arg);
    } catch (Exception ex) {
      throw new PropertySetterException(ex);
    }
  }

  public AggregationType computeAggregationType(String name) {
    String cName = capitalizeFirstLetter(name);

    Method addMethod = getMethod("add" + cName);

    // if the
    if (addMethod != null) {
      AggregationType type = computeRawAggregationType(addMethod);
      switch (type) {
      case NOT_FOUND:
        return AggregationType.NOT_FOUND;
      case AS_BASIC_PROPERTY:
        return AggregationType.AS_BASIC_PROPERTY_COLLECTION;
      case AS_COMPLEX_PROPERTY:
        return AggregationType.AS_COMPLEX_PROPERTY_COLLECTION;
      }
    }

    Method setterMethod = findSetterMethod(name);
    if (setterMethod != null) {
      return computeRawAggregationType(setterMethod);
    } else {
      // we have failed
      return AggregationType.NOT_FOUND;
    }
  }

  private Method findAdderMethod(String name) {
    name = capitalizeFirstLetter(name);
    Method adderMethod = getMethod("add" + name);
    return adderMethod;
  }

  private Method findSetterMethod(String name) {
    String dName = Introspector.decapitalize(name);
    PropertyDescriptor propertyDescriptor = getPropertyDescriptor(dName);
    if (propertyDescriptor != null) {
      return propertyDescriptor.getWriteMethod();
    } else {
      return null;
    }
  }

  Class<?> getParameterClassForMethod(Method method) {
    if (method == null) {
      return null;
    }
    Class[] classArray = method.getParameterTypes();
    if (classArray.length != 1) {
      return null;
    } else {
      return classArray[0];
    }
  }

  private AggregationType computeRawAggregationType(Method method) {
    Class<?> parameterClass = getParameterClassForMethod(method);
    if (parameterClass == null) {
      return AggregationType.NOT_FOUND;
    } else {
      Package p = parameterClass.getPackage();
      if (parameterClass.isPrimitive()) {
        return AggregationType.AS_BASIC_PROPERTY;
      } else if (p != null && "java.lang".equals(p.getName())) {
        return AggregationType.AS_BASIC_PROPERTY;
      } else if (isBuildableFromString(parameterClass)) {
        return AggregationType.AS_BASIC_PROPERTY;
      } else if (parameterClass.isEnum()) {
        return AggregationType.AS_BASIC_PROPERTY;
      } else {
        return AggregationType.AS_COMPLEX_PROPERTY;
      }
    }
  }

  public Class findUnequivocallyInstantiableClass(
      IADataForComplexProperty actionData) {
    Class<?> clazz;
    AggregationType at = actionData.getAggregationType();
    switch (at) {
    case AS_COMPLEX_PROPERTY:
      Method setterMethod = findSetterMethod(actionData
          .getComplexPropertyName());
      clazz = getParameterClassForMethod(setterMethod);
      if (clazz != null && isUnequivocallyInstantiable(clazz)) {
        return clazz;
      } else {
        return null;
      }
    case AS_COMPLEX_PROPERTY_COLLECTION:
      Method adderMethod = findAdderMethod(actionData.getComplexPropertyName());
      clazz = getParameterClassForMethod(adderMethod);
      if (clazz != null && isUnequivocallyInstantiable(clazz)) {
        return clazz;
      } else {
        return null;
      }
    default:
      throw new IllegalArgumentException(at
          + " is not valid type in this method");
    }
  }

  boolean isUnequivocallyInstantiable(Class<?> clazz) {
    if (clazz.isInterface()) {
      return false;
    }
    // checking for constructors would be slightly more elegant, but in
    // classes
    // without any declared constructors, Class.getConstructor() returns null.
    Object o;
    try {
      o = clazz.newInstance();
      if (o != null) {
        return true;
      } else {
        return false;
      }
    } catch (InstantiationException e) {
      return false;
    } catch (IllegalAccessException e) {
      return false;
    }
  }

  public Class getObjClass() {
    return objClass;
  }

  public void addComplexProperty(String name, Object complexProperty) {
    Method adderMethod = findAdderMethod(name);
    // first let us use the addXXX method
    if (adderMethod != null) {
      Class[] paramTypes = adderMethod.getParameterTypes();
      if (!isSanityCheckSuccessful(name, adderMethod, paramTypes,
          complexProperty)) {
        return;
      }
      invokeMethodWithSingleParameterOnThisObject(adderMethod, complexProperty);
    } else {
      addError("Could not find method [" + "add" + name + "] in class ["
          + objClass.getName() + "].");
    }
  }

  void invokeMethodWithSingleParameterOnThisObject(Method method,
      Object parameter) {
    Class ccc = parameter.getClass();
    try {
      method.invoke(this.obj, parameter);
    } catch (Exception e) {
      addError("Could not invoke method " + method.getName() + " in class "
          + obj.getClass().getName() + " with parameter of type "
          + ccc.getName(), e);
    }
  }

  @SuppressWarnings("unchecked")
  public void addBasicProperty(String name, String strValue) {

    if (strValue == null) {
      return;
    }

    name = capitalizeFirstLetter(name);
    Method adderMethod = getMethod("add" + name);

    if (adderMethod == null) {
      addError("No adder for property [" + name + "].");
      return;
    }

    Class[] paramTypes = adderMethod.getParameterTypes();
    isSanityCheckSuccessful(name, adderMethod, paramTypes, strValue);

    Object arg;
    try {
      arg = convertArg(strValue, paramTypes[0]);
    } catch (Throwable t) {
      addError("Conversion to type [" + paramTypes[0] + "] failed. ", t);
      return;
    }
    if (arg != null) {
      invokeMethodWithSingleParameterOnThisObject(adderMethod, strValue);
    }
  }

  public void setComplexProperty(String name, Object complexProperty) {
    String dName = Introspector.decapitalize(name);
    PropertyDescriptor propertyDescriptor = getPropertyDescriptor(dName);

    if (propertyDescriptor == null) {
      addWarn("Could not find PropertyDescriptor for [" + name + "] in "
          + objClass.getName());

      return;
    }

    Method setter = propertyDescriptor.getWriteMethod();

    if (setter == null) {
      addWarn("Not setter method for property [" + name + "] in "
          + obj.getClass().getName());

      return;
    }

    Class[] paramTypes = setter.getParameterTypes();

    if (!isSanityCheckSuccessful(name, setter, paramTypes, complexProperty)) {
      return;
    }
    try {
      invokeMethodWithSingleParameterOnThisObject(setter, complexProperty);

    } catch (Exception e) {
      addError("Could not set component " + obj + " for parent component "
          + obj, e);
    }
  }

  private boolean isSanityCheckSuccessful(String name, Method method,
      Class<?>[] params, Object complexProperty) {
    Class ccc = complexProperty.getClass();
    if (params.length != 1) {
      addError("Wrong number of parameters in setter method for property ["
          + name + "] in " + obj.getClass().getName());

      return false;
    }

    if (!params[0].isAssignableFrom(complexProperty.getClass())) {
      addError("A \"" + ccc.getName() + "\" object is not assignable to a \""
          + params[0].getName() + "\" variable.");
      addError("The class \"" + params[0].getName() + "\" was loaded by ");
      addError("[" + params[0].getClassLoader() + "] whereas object of type ");
      addError("\"" + ccc.getName() + "\" was loaded by ["
          + ccc.getClassLoader() + "].");
      return false;
    }

    return true;
  }

  private String capitalizeFirstLetter(String name) {
    return name.substring(0, 1).toUpperCase() + name.substring(1);
  }

  /**
   * Convert <code>val</code> a String parameter to an object of a given type.
   */
  protected Object convertArg(String val, Class<?> type) {
    if (val == null) {
      return null;
    }
    String v = val.trim();
    if (String.class.isAssignableFrom(type)) {
      return val;
    } else if (Integer.TYPE.isAssignableFrom(type)) {
      return new Integer(v);
    } else if (Long.TYPE.isAssignableFrom(type)) {
      return new Long(v);
    } else if (Float.TYPE.isAssignableFrom(type)) {
      return new Float(v);
    } else if (Double.TYPE.isAssignableFrom(type)) {
      return new Double(v);
    } else if (Boolean.TYPE.isAssignableFrom(type)) {
      if ("true".equalsIgnoreCase(v)) {
        return Boolean.TRUE;
      } else if ("false".equalsIgnoreCase(v)) {
        return Boolean.FALSE;
      }
    } else if (type.isEnum()) {
      return convertEnum(val, type);
    } else if (isBuildableFromString(type)) {
      return buildFromString(type, val);
    }

    return null;
  }

  boolean isBuildableFromString(Class<?> parameterClass) {
    try {
      Method valueOfMethod = parameterClass.getMethod(CoreConstants.VALUE_OF,
          STING_CLASS_PARAMETER);
      int mod = valueOfMethod.getModifiers();
      if (Modifier.isStatic(mod)) {
        return true;
      }
    } catch (SecurityException e) {
      // nop
    } catch (NoSuchMethodException e) {
      // nop
    }
    return false;
  }

  Object buildFromString(Class<?> type, String val) {
    try {
      Method valueOfMethod = type.getMethod(CoreConstants.VALUE_OF,
          STING_CLASS_PARAMETER);
      return valueOfMethod.invoke(null, val);
    } catch (Exception e) {
      addError("Failed to invoke " + CoreConstants.VALUE_OF
          + "{} method in class [" + type.getName() + "] with value [" + val
          + "]");
      return null;
    }
  }

  protected Object convertEnum(String val, Class<?> type) {
    try {
      Method m = type.getMethod(CoreConstants.VALUE_OF, STING_CLASS_PARAMETER);
      return m.invoke(null, val);
    } catch (Exception e) {
      addError("Failed to convert value [" + val + "] to enum ["
          + type.getName() + "]", e);
    }
    return null;
  }

  protected Method getMethod(String methodName) {
    if (methodDescriptors == null) {
      introspect();
    }

    for (int i = 0; i < methodDescriptors.length; i++) {
      if (methodName.equals(methodDescriptors[i].getName())) {
        return methodDescriptors[i].getMethod();
      }
    }

    return null;
  }

  protected PropertyDescriptor getPropertyDescriptor(String name) {
    if (propertyDescriptors == null) {
      introspect();
    }

    for (int i = 0; i < propertyDescriptors.length; i++) {
      // System.out.println("Comparing " + name + " against "
      // + propertyDescriptors[i].getName());
      if (name.equals(propertyDescriptors[i].getName())) {
        // System.out.println("matched");
        return propertyDescriptors[i];
      }
    }

    return null;
  }

  public Object getObj() {
    return obj;
  }

  public <T extends Annotation> T getAnnotation(String name,
      Class<T> annonationClass, AggregationType aggregationType) {
    String cName = capitalizeFirstLetter(name);
    Method relevantMethod;
    if (aggregationType == AggregationType.AS_COMPLEX_PROPERTY_COLLECTION) {
      relevantMethod = getMethod("add" + cName);
    } else if (aggregationType == AggregationType.AS_COMPLEX_PROPERTY) {
      relevantMethod = findSetterMethod(cName);
    } else {
      throw new IllegalStateException(aggregationType + " not allowed here");
    }
    if (relevantMethod != null) {
      return relevantMethod.getAnnotation(annonationClass);
    } else {
      return null;
    }
  }

  public String getDefaultClassNameByAnnonation(String name,
      AggregationType aggregationType) {

    DefaultClass defaultClassAnnon = getAnnotation(name, DefaultClass.class,
        aggregationType);
    if (defaultClassAnnon != null) {
      Class defaultClass = defaultClassAnnon.value();
      if (defaultClass != null) {
        return defaultClass.getName();
      }
    }
    return null;
  }
}
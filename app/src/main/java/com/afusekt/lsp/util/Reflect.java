package com.afusekt.lsp.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minimal reflection helpers — avoids XposedHelpers (XposedBridge missing in libxposed). */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> cls, String name) {
        try {
            Field field = findField(cls, name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getObjectField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object newInstance(Class<?> cls, Object... args) {
        try {
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (!compatible(types, args)) {
                    continue;
                }
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
            throw new NoSuchMethodException(cls.getName() + ".<init>");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static Object callMethod(Object target, String name, Object... args) {
        try {
            Class<?> cls = target.getClass();
            while (cls != null) {
                for (Method method : cls.getDeclaredMethods()) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (!compatible(types, args)) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
                cls = cls.getSuperclass();
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> cls, String name, Object... args) {
        try {
            for (Method method : cls.getDeclaredMethods()) {
                if (!name.equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (!compatible(types, args)) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(null, args);
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Object arg = args[i];
            Class<?> type = types[i];
            if (arg == null) {
                if (type.isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> expect = box(type);
            if (!expect.isInstance(arg) && !type.isAssignableFrom(arg.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }
}

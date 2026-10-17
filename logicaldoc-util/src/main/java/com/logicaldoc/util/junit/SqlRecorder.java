package com.logicaldoc.util.junit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.ClassUtils;

import com.logicaldoc.util.spring.MainContext;

/**
 * Records the SQL text that reaches the database. The in-memory HSQLDB used by
 * the tests can't show that a quoted value breaks out of its string literal on
 * other databases, so tests use this to check that a value was bound as a
 * parameter instead of being written into the SQL.
 *
 * Hook it in by overriding {@link AbstractTestCase#buildApplicationContext()}
 * with {@link #buildApplicationContext()}, or with
 * {@link #buildApplicationContext(Class...)} where the test case uses its own
 * configuration class.
 */
public class SqlRecorder implements BeanPostProcessor {

    private static final Set<String> SQL_METHODS = Set.of("prepareStatement", "prepareCall", "nativeSQL", "execute",
            "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final List<String> statements = new CopyOnWriteArrayList<>();

    /**
     * Builds the same context as {@link AbstractTestCase}, with the
     * <code>dataSource</code> bean wrapped so that its SQL gets recorded
     *
     * @return the application context
     */
    public ApplicationContext buildApplicationContext() {
        return buildApplicationContext(MainContext.class);
    }

    /**
     * Builds a context from the given configuration classes, with the
     * <code>dataSource</code> bean wrapped so that its SQL gets recorded
     *
     * @param componentClasses the configuration classes
     *
     * @return the application context
     */
    public ApplicationContext buildApplicationContext(Class<?>... componentClasses) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().addBeanPostProcessor(this);
        context.register(componentClasses);
        context.refresh();
        return context;
    }

    public List<String> getStatements() {
        return statements;
    }

    public void clear() {
        statements.clear();
    }

    /**
     * Asserts that some SQL was recorded and that none of it contains the
     * value, as it is or with its quotes doubled, ignoring case
     *
     * @param value the value that should have been bound as a parameter
     */
    public void assertNotInlined(String value) {
        assertFalse("No SQL was recorded", statements.isEmpty());

        String plain = value.toLowerCase();
        String quoted = plain.replace("'", "''");
        statements.stream().filter(sql -> sql.toLowerCase().contains(plain) || sql.toLowerCase().contains(quoted))
                .findFirst().ifPresent(sql -> fail("The value was written into the SQL: " + sql));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if ("dataSource".equals(beanName) && bean instanceof DataSource)
            return record(bean);
        return bean;
    }

    private Object record(Object target) {
        return Proxy.newProxyInstance(getClass().getClassLoader(),
                ClassUtils.getAllInterfacesForClass(target.getClass(), getClass().getClassLoader()),
                (proxy, method, args) -> {
                    if ("equals".equals(method.getName()) && args != null && args.length == 1)
                        return proxy == args[0];

                    if (args != null && args.length > 0 && args[0] instanceof String sql
                            && SQL_METHODS.contains(method.getName()))
                        statements.add(sql);

                    Object result;
                    try {
                        result = method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }

                    // Follow the connections and the statements they create
                    if (result instanceof Connection || result instanceof Statement)
                        return record(result);
                    return result;
                });
    }
}

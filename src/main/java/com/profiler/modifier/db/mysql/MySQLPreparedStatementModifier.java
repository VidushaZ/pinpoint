package com.profiler.modifier.db.mysql;

import com.profiler.config.ProfilerConstant;
import com.profiler.interceptor.Interceptor;
import com.profiler.interceptor.bci.ByteCodeInstrumentor;
import com.profiler.interceptor.bci.InstrumentClass;
import com.profiler.interceptor.bci.InstrumentException;
import com.profiler.interceptor.bci.NotFoundInstrumentException;
import com.profiler.modifier.AbstractModifier;
import com.profiler.modifier.db.interceptor.PreparedStatementBindVariableInterceptor;
import com.profiler.modifier.db.interceptor.PreparedStatementExecuteQueryInterceptor;
import com.profiler.trace.DatabaseRequestTracer;
import com.profiler.util.ExcludeBindVariableFilter;
import com.profiler.util.JavaAssistUtils;
import com.profiler.util.PreparedStatementUtils;
import javassist.CtClass;
import javassist.CtConstructor;

import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLPreparedStatementModifier extends AbstractModifier {
    private final Logger logger = Logger.getLogger(MySQLPreparedStatementModifier.class.getName());
    private final String[] excludes = new String[]{"setRowId", "setNClob", "setSQLXML"};

    public MySQLPreparedStatementModifier(ByteCodeInstrumentor byteCodeInstrumentor) {
        super(byteCodeInstrumentor);
    }

    public String getTargetClass() {
        return "com/mysql/jdbc/PreparedStatement";
        // 상속관계일 경우 byte코드를 수정할 객체를 타겟으로해야 됨.
//        return "com/mysql/jdbc/JDBC4PreparedStatement";
    }

    public byte[] modify(ClassLoader classLoader, String javassistClassName, ProtectionDomain protectedDomain, byte[] classFileBuffer) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info("Modifing. " + javassistClassName);
        }

        checkLibrary(classLoader, javassistClassName);
        try {
            InstrumentClass preparedStatement = byteCodeInstrumentor.getClass(javassistClassName);

            Interceptor interceptor = new PreparedStatementExecuteQueryInterceptor();
            int id = preparedStatement.addInterceptor("executeQuery", null, interceptor);
            preparedStatement.reuseInterceptor("executeUpdate", null, id);

            preparedStatement.addTraceVariable("__url", "__setUrl", "__getUrl", "java.lang.String");
            preparedStatement.addTraceVariable("__sql", "__setSql", "__getSql", "java.lang.String");

            preparedStatement.addTraceVariable("__bindValue", "__setBindValue", "__getBindValue", "java.util.Map", "java.util.Collections.synchronizedMap(new java.util.HashMap());");
            bindVariableIntercept(preparedStatement, classLoader, protectedDomain);

            return preparedStatement.toBytecode();
        } catch (InstrumentException e) {
            if (logger.isLoggable(Level.WARNING)) {
                logger.log(Level.WARNING, this.getClass().getSimpleName() + " modify fail. Cause:" + e.getMessage(), e);
            }
            return null;
        }

//		Interceptor interceptor = newInterceptor(classLoader, protectedDomain, "com.profiler.modifier.db.mysql.interceptors.ExecuteMethodInterceptor");
//		if (interceptor == null) {
//			return null;
//		}
//
//		byteCodeInstrumentor.checkLibrary(classLoader, javassistClassName);
//
//		InstrumentClass aClass = byteCodeInstrumentor.getClass(javassistClassName);
//		aClass.addInterceptor("executeQuery", null, interceptor);

//		return changeMethod(javassistClassName, classFileBuffer);
    }

    private void bindVariableIntercept(InstrumentClass preparedStatement, ClassLoader classLoader, ProtectionDomain protectedDomain) throws InstrumentException {
        ExcludeBindVariableFilter exclude = new ExcludeBindVariableFilter(excludes);
        List<Method> bindMethod = PreparedStatementUtils.findBindVariableSetMethod(exclude);

        Interceptor interceptor = new PreparedStatementBindVariableInterceptor();
        int interceptorId = -1;
        for (Method method : bindMethod) {
            String methodName = method.getName();
            String[] parameterType = JavaAssistUtils.getParameterType(method.getParameterTypes());
            try {
                if (interceptorId == -1) {
                    interceptorId = preparedStatement.addInterceptor(methodName, parameterType, interceptor);
                } else {
                    preparedStatement.reuseInterceptor(methodName, parameterType, interceptorId);
                }
            } catch (NotFoundInstrumentException e) {
                // bind variable setter메소드를 못찾을 경우는 그냥 경고만 표시, 에러 아님.
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "bindVariable api not found. Cause:" + e.getMessage(), e);
                }
            }
        }

    }


    private void updateConstructor(CtClass cc) throws Exception {
        CtConstructor[] constructorList = cc.getConstructors();
        if (constructorList.length == 3) {
            for (CtConstructor constructor : constructorList) {
                CtClass params[] = constructor.getParameterTypes();
                if (params.length == 3) {
                    constructor.insertBefore("{" + DatabaseRequestTracer.FQCN + ".putSqlQuery(" + ProfilerConstant.REQ_DATA_TYPE_DB_QUERY + ",$2); }");
                }
            }
        }
    }


}

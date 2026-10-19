package com.smartexam.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class QuestionOptionPolicyTest {
    private static final String OPTIONS = "[{\"key\":\"A\",\"text\":\"一\"},{\"key\":\"E\",\"text\":\"五\"},{\"key\":\"自定义\",\"text\":\"六\"}]";
    private Object invoke(String method, Class<?>[] types, Object... args) throws Throwable {
        Class<?> policy;
        try { policy=Class.forName("com.smartexam.service.QuestionOptionPolicy"); }
        catch (ClassNotFoundException e) { throw new AssertionError("Option policy not implemented", e); }
        try { return policy.getMethod(method, types).invoke(null, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    @Test void acceptsEAndCustomKeysWithoutRelabeling() throws Throwable {
        assertThat(invoke("validKeys", new Class[]{String.class}, OPTIONS)).isEqualTo(Set.of("A","E","自定义"));
        assertThat(invoke("normalizeAnswer",new Class[]{String.class,String.class}," E ",OPTIONS)).isEqualTo("E");
        assertThat(invoke("normalizeAnswer",new Class[]{String.class,String.class},"自定义",OPTIONS)).isEqualTo("自定义");
    }
    @Test void rejectsMissingDuplicateBlankOrMalformedOptions() {
        for(String data:new String[]{"[]","null","{}","[", "[{\"key\":\"A\",\"text\":\"一\"}]",
                "[{\"key\":\"A\",\"text\":\"一\"},{\"key\":\"A\",\"text\":\"二\"}]",
                "[{\"key\":\" \",\"text\":\"一\"},{\"key\":\"B\",\"text\":\"二\"}]"})
            assertThatThrownBy(()->invoke("validKeys",new Class[]{String.class},data)).isInstanceOf(com.smartexam.exception.BusinessException.class);
    }
    @Test void rejectsUnknownAnswerAndPreservesEmptySemantics() throws Throwable {
        assertThatThrownBy(()->invoke("normalizeAnswer",new Class[]{String.class,String.class},"D",OPTIONS)).isInstanceOf(com.smartexam.exception.BusinessException.class);
        assertThat(invoke("normalizeAnswer",new Class[]{String.class,String.class},null,OPTIONS)).isNull();
        assertThat(invoke("normalizeAnswer",new Class[]{String.class,String.class},"  ",OPTIONS)).isEqualTo("");
    }
}

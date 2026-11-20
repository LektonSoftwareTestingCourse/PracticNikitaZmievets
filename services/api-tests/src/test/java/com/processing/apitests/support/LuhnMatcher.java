package com.processing.apitests.support;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * Hamcrest-матчер контрольной цифры Луна для PAN (ТЗ-05 §4: PAN = BIN + 9 цифр + контрольная цифра Луна).
 */
public final class LuhnMatcher extends TypeSafeMatcher<String> {

    private LuhnMatcher() {
    }

    public static Matcher<String> passesLuhnCheck() {
        return new LuhnMatcher();
    }

    @Override
    protected boolean matchesSafely(String pan) {
        if (!pan.matches("\\d+")) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("PAN с корректной контрольной цифрой Луна");
    }
}

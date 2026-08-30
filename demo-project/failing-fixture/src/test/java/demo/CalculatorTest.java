package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalculatorTest {

    @Test
    void addsPositiveAndNegativeNumbers() {
        assertEquals(7, Calculator.add(3, 4));
        assertEquals(-1, Calculator.add(3, -4));
    }
}

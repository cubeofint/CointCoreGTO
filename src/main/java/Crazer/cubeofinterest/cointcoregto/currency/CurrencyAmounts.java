package Crazer.cubeofinterest.cointcoregto.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class CurrencyAmounts {
    private CurrencyAmounts() {
    }

    public static long parse(String value, CurrencyDescriptor descriptor) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Пустая сумма");
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Некорректная сумма");
        }

        if (parsed.signum() < 0) {
            throw new IllegalArgumentException("Сумма не может быть отрицательной");
        }

        try {
            parsed = parsed.setScale(descriptor.fractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Допустимо знаков после запятой: " + descriptor.fractionDigits());
        }

        long minor;
        try {
            minor = parsed.movePointRight(descriptor.fractionDigits()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Сумма слишком большая");
        }

        if (minor > descriptor.maximumBalance()) {
            throw new IllegalArgumentException("Сумма превышает допустимый максимум");
        }
        return minor;
    }

    public static String format(long amount, CurrencyDescriptor descriptor) {
        BigDecimal value = BigDecimal.valueOf(amount, descriptor.fractionDigits());
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setDecimalSeparator('.');
        String pattern = descriptor.fractionDigits() == 0
                ? "0"
                : "0." + "0".repeat(descriptor.fractionDigits());
        DecimalFormat format = new DecimalFormat(pattern, symbols);
        format.setGroupingUsed(false);
        String text = format.format(value);
        return descriptor.symbol().isBlank() ? text : text + " " + descriptor.symbol();
    }
}

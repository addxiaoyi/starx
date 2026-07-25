/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import java.util.List;

public interface BytesValidator {
    public boolean validate(byte[] var1);

    public static final class Logical
    implements BytesValidator {
        private final List<BytesValidator> validatorList;
        private final Operator operator;

        public Logical(List<BytesValidator> validatorList, Operator operator) {
            if (validatorList.isEmpty()) {
                throw new IllegalArgumentException("must contain at least 1 element");
            }
            if (operator == Operator.NOT && validatorList.size() != 1) {
                throw new IllegalArgumentException("not operator can only be applied to single element");
            }
            this.validatorList = validatorList;
            this.operator = operator;
        }

        @Override
        public boolean validate(byte[] byteArrayToValidate) {
            if (this.operator == Operator.NOT) {
                return !this.validatorList.get(0).validate(byteArrayToValidate);
            }
            boolean bool = this.operator != Operator.OR;
            block3: for (BytesValidator bytesValidator : this.validatorList) {
                switch (this.operator) {
                    case AND: {
                        bool &= bytesValidator.validate(byteArrayToValidate);
                        continue block3;
                    }
                }
                bool |= bytesValidator.validate(byteArrayToValidate);
            }
            return bool;
        }

        static enum Operator {
            OR,
            AND,
            NOT;

        }
    }

    public static final class PrePostFix
    implements BytesValidator {
        private final byte[] pfix;
        private final boolean startsWith;

        public PrePostFix(boolean startsWith, byte ... pfix) {
            this.pfix = pfix;
            this.startsWith = startsWith;
        }

        @Override
        public boolean validate(byte[] byteArrayToValidate) {
            if (this.pfix.length > byteArrayToValidate.length) {
                return false;
            }
            for (int i = 0; i < this.pfix.length; ++i) {
                if (this.startsWith && this.pfix[i] != byteArrayToValidate[i]) {
                    return false;
                }
                if (this.startsWith || this.pfix[i] == byteArrayToValidate[byteArrayToValidate.length - this.pfix.length + i]) continue;
                return false;
            }
            return true;
        }
    }

    public static final class IdenticalContent
    implements BytesValidator {
        private final byte refByte;
        private final Mode mode;

        IdenticalContent(byte refByte, Mode mode) {
            this.refByte = refByte;
            this.mode = mode;
        }

        @Override
        public boolean validate(byte[] byteArrayToValidate) {
            for (byte b : byteArrayToValidate) {
                if (this.mode == Mode.NONE_OF && b == this.refByte) {
                    return false;
                }
                if (this.mode == Mode.ONLY_OF && b != this.refByte) {
                    return false;
                }
                if (this.mode != Mode.NOT_ONLY_OF || b == this.refByte) continue;
                return true;
            }
            return this.mode == Mode.NONE_OF || this.mode == Mode.ONLY_OF;
        }

        static enum Mode {
            ONLY_OF,
            NONE_OF,
            NOT_ONLY_OF;

        }
    }

    public static final class Length
    implements BytesValidator {
        private final int refLength;
        private final Mode mode;

        public Length(int refLength, Mode mode) {
            this.refLength = refLength;
            this.mode = mode;
        }

        @Override
        public boolean validate(byte[] byteArrayToValidate) {
            switch (this.mode) {
                case GREATER_OR_EQ_THAN: {
                    return byteArrayToValidate.length >= this.refLength;
                }
                case SMALLER_OR_EQ_THAN: {
                    return byteArrayToValidate.length <= this.refLength;
                }
            }
            return byteArrayToValidate.length == this.refLength;
        }

        static enum Mode {
            SMALLER_OR_EQ_THAN,
            GREATER_OR_EQ_THAN,
            EXACT;

        }
    }
}

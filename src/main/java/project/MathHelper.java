package project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.DoubleUnaryOperator;

public class MathHelper {

    private static final Set<Character> OPERATORS = new HashSet<>(Arrays.asList('+', '-', '*', '/', '^'));
    private static final Map<String, DoubleUnaryOperator> FUNCTIONS = new HashMap<>();
    static {
        FUNCTIONS.put("floor", Math::floor);
        FUNCTIONS.put("ceil", Math::ceil);
        FUNCTIONS.put("round", Math::round);
        FUNCTIONS.put("abs", Math::abs);
        FUNCTIONS.put("sqrt", Math::sqrt);
        FUNCTIONS.put("cbrt", Math::cbrt);
        FUNCTIONS.put("log", Math::log);
        FUNCTIONS.put("sin", Math::sin);
        FUNCTIONS.put("cos", Math::cos);
        FUNCTIONS.put("tan", Math::tan);
    }

    public static double parse(String expression) throws IllegalArgumentException {
        // tokenize expression and validate it
        List<String> tokens = tokenize(expression.replaceAll("\\s", "").toLowerCase());

        // stacks for handling nested expressions
        Stack<Stack<Double>> expNumStack = new Stack<Stack<Double>>();
        Stack<Stack<String>> expOpStack = new Stack<Stack<String>>();

        // push initial stacks for outermost expression
        expNumStack.push(new Stack<Double>());
        expOpStack.push(new Stack<String>());

        // expect number stack for functions across nested expressions
        Stack<Boolean> expectNumberStack = new Stack<Boolean>();

        // push tokens onto stacks
        for (int i = 0; i < tokens.size() || expNumStack.peek().size() > 1 || !expOpStack.peek().isEmpty(); i++) {
            // make sure we have a token to work with
            if (i < tokens.size()) {
                String t = tokens.get(i);
                // handle nested expressions first
                if (t.equals("(")) {
                    // push new stacks for nested expression
                    expNumStack.push(new Stack<Double>());
                    expOpStack.push(new Stack<String>());
                    expectNumberStack.push(false);
                    continue;
                } else if (t.equals(")")) {
                    // pop stacks for nested expression
                    if (expNumStack.isEmpty() || expOpStack.isEmpty()) { throw new IllegalArgumentException("Unmatched closing parenthesis in nested expression"); }
                    Stack<Double> numStack = expNumStack.pop();
                    Stack<String> opStack = expOpStack.pop();
                    if (!opStack.isEmpty() || numStack.size() > 1) { throw new IllegalArgumentException("Invalid nested expression encountered"); }
                    expectNumberStack.pop();
                    // set the current token to instead be the result of the nested expression
                    t = numStack.pop().toString();
                }

                // then check if a function is expecting a number next
                if (expectNumberStack.isEmpty() ? false : expectNumberStack.peek()) {
                    if (isNumber(t)) {
                        expNumStack.peek().push(Double.parseDouble(t));
                    } else {
                        throw new IllegalArgumentException("Invalid token, expected number for function: " + t);
                    }

                    // then check if token is a number, operator, or function
                } else if (isNumber(t)) {
                    expNumStack.peek().push(Double.parseDouble(t));

                } else if (isOperator(t)) {
                    expOpStack.peek().push(t);

                } else if (isFunction(t)) {
                    expOpStack.peek().push(t);

                    // if not, throw an exception
                } else {
                    throw new IllegalArgumentException("Invalid token: " + t);
                }
            }
            // check if we can apply an operator
            boolean reeval = false;
            do {
                if (expNumStack.peek().size() > 1 && !expOpStack.peek().isEmpty() && !isFunction(expOpStack.peek().peek())) {
                    double b = expNumStack.peek().pop();
                    double a = expNumStack.peek().pop();
                    String op = expOpStack.peek().pop();
                    expNumStack.peek().push(applyOperator(a, b, op));
                    reeval = false;

                    // then check if a function is expecting a number next
                } else if (!expOpStack.peek().isEmpty() && isFunction(expOpStack.peek().peek()) && (expectNumberStack.isEmpty() ? true : !expectNumberStack.peek())) {
                    expectNumberStack.push(true);

                    // then check if we can apply a function
                } else if (!expOpStack.peek().isEmpty() && isFunction(expOpStack.peek().peek()) && !expectNumberStack.isEmpty() && expectNumberStack.peek()) {
                    double arg = expNumStack.peek().pop();
                    String name = expOpStack.peek().pop();
                    expNumStack.peek().push(applyFunction(arg, name));
                    // re-evaluate the expression in to use the function
                    reeval = expectNumberStack.pop();
                } else {
                    reeval = false;
                }

            } while (reeval);
        }
        if (expNumStack.size() != 1 || expNumStack.peek().size() != 1) { throw new IllegalArgumentException("Invalid expression: " + expression); }
        return expNumStack.peek().pop();
    }

    private static List<String> tokenize(String expression) throws IllegalArgumentException {
        List<String> tokens = new ArrayList<String>();
        Stack<Character> pStack = new Stack<Character>();
        String curToken = "";
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                if (curToken.length() > 0) {
                    tokens.add(new String(curToken));
                    curToken = "";
                }
                tokens.add(String.valueOf(c));
                pStack.push(c);
                continue;
            } else if (c == ')') {
                if (curToken.length() > 0) {
                    tokens.add(new String(curToken));
                    curToken = "";
                }
                if (tokens.size()-1 >= 0 && tokens.get(tokens.size() - 1).equals("(")) { throw new IllegalArgumentException("Empty parenthesis block"); }
                if (pStack.isEmpty()) { throw new IllegalArgumentException("Unmatched closing parenthesis"); }
                tokens.add(String.valueOf(c));
                pStack.pop();
                continue;
            } else if (Character.isDigit(c) || c == '.' || (c == '-' && curToken.length() == 0)) {
                curToken += c;
                continue;
            } else if (OPERATORS.contains(c)) {
                if (curToken.length() > 0) {
                    tokens.add(new String(curToken));
                    curToken = "";
                }
                tokens.add(String.valueOf(c));
                continue;
            } else if (Character.isLetter(c)) {
                if (curToken.length() > 0) {
                    tokens.add(new String(curToken));
                    curToken = "";
                }
                curToken += c;
                while (i + 1 < expression.length() && (Character.isLetter(expression.charAt(i + 1)) || expression.charAt(i + 1) == '(' || expression.charAt(i + 1) == ')')) {
                    if (expression.charAt(i + 1) == '(') {
                        if (FUNCTIONS.containsKey(curToken)) {
                            tokens.add(curToken);
                            curToken = "";
                            pStack.push(expression.charAt(++i));
                            tokens.add(String.valueOf(expression.charAt(i)));
                            break;
                        } else {
                            throw new IllegalArgumentException("Unknown function: " + curToken);
                        }
                    } else if (Character.isLetter(expression.charAt(i + 1))) {
                        curToken += expression.charAt(++i);
                    } else {
                        throw new IllegalArgumentException("Invalid character while parsing function: " + c);
                    }
                }
                continue;
            } else if (!Character.isWhitespace(c)) { throw new IllegalArgumentException("Invalid character: " + c); }
        }
        if (curToken.length() > 0) {
            tokens.add(curToken);
        }

        // bc im lazy and don't want to implement operator precedence (and this is more fun lol),
        // do a final pass to inset parentheses around * and / operators and ^ lol
        // the expression should already be valid by this point
        for (int loop = 0; loop < 2; loop++) {
            // need to loop over tokens twice, first doing ^, then / and * the 2nd time
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                // first add parentheses around ^ and its operands
                if ((loop == 0 && t.equals("^")) || (loop == 1 && (t.equals("/") || t.equals("*")))) {
                    // left side...
                    // if its just a number, insert a open parenthesis before it
                    if (i - 1 > 0 && isNumber(tokens.get(i - 1))) {
                        tokens.add(i - 1, "(");
                        i++;
                        // else its another parenthesis block or a function
                    } else if (i - 1 > 0 && tokens.get(i - 1).equals(")")) {
                        int j = i - 1;
                        int pCount = 1;
                        while (j > 0 && pCount > 0) {
                            j--;
                            if (tokens.get(j).equals(")")) {
                                pCount++;
                            } else if (tokens.get(j).equals("(")) {
                                pCount--;
                            }
                            if (pCount == 0) {
                                // if its a function, insert before the function
                                if (j - 1 > 0 && isFunction(tokens.get(j - 1))) {
                                    tokens.add(j - 1, "(");
                                    i++;
                                    break;
                                }
                                // else insert before the parenthesis block
                                tokens.add(j, "(");
                                i++;
                                break;
                            }
                        }
                        // else, insert parenthesis at the beginning of the expression
                    } else {
                        tokens.add(0, "(");
                        i++;
                    }
                    // right side...
                    // if its just a number, insert a close parenthesis after it
                    if (i + 1 < tokens.size() && isNumber(tokens.get(i + 1))) {
                        if (i + 2 < tokens.size()) {
                            tokens.add(i + 2, ")");
                        } else {
                            tokens.add(")");
                        }
                        // else its another parenthesis block
                    } else if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(")) {
                        int j = i + 1;
                        int pCount = 1;
                        while (j < tokens.size() && pCount > 0) {
                            j++;
                            if (tokens.get(j).equals("(")) {
                                pCount++;
                            } else if (tokens.get(j).equals(")")) {
                                pCount--;
                            }
                            if (pCount == 0) {
                                // insert after the parenthesis block
                                if (j + 1 < tokens.size()) {
                                    tokens.add(j + 1, ")");
                                } else {
                                    tokens.add(")");
                                }
                                break;
                            }
                        }
                        // else, insert parenthesis at the end of the expression
                    } else {
                        tokens.add(")");
                    }
                }
            }
        }

        return tokens;
    }

    private static boolean isNumber(String token) {
        return token.matches("-?[0-9]+(\\.[0-9]+)?");
    }

    private static boolean isOperator(String token) {
        return token.length() == 1 && OPERATORS.contains(token.charAt(0));
    }

    private static boolean isFunction(String token) {
        return FUNCTIONS.containsKey(token);
    }

    private static double applyOperator(double a, double b, String op) throws IllegalArgumentException {
        switch (op) {
        case "+":
            return a + b;
        case "-":
            return a - b;
        case "*":
            return a * b;
        case "/":
            return a / b;
        case "^":
            return Math.pow(a, b);
        default:
            throw new IllegalArgumentException("Unknown operator: " + op);
        }
    }

    private static double applyFunction(double arg, String name) throws IllegalArgumentException {
        DoubleUnaryOperator function = FUNCTIONS.get(name);
        if (function == null) { throw new IllegalArgumentException("Unknown function: " + name); }
        return function.applyAsDouble(arg);
    }
}
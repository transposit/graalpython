/*
 * Copyright (c) 2017-2020, Oracle and/or its affiliates.
 * Copyright (c) 2014 by Bart Kiers
 *
 * The MIT License (MIT)
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
/*
 * Project      : python3-parser; an ANTLR4 grammar for Python 3
 *                https://github.com/bkiers/python3-parser
 * Developed by : Bart Kiers, bart@big-o.nl
 */
grammar Python3;

// All comments that start with "///" are copy-pasted from
// The Python Language Reference

tokens { INDENT, DEDENT, INDENT_ERROR, TAB_ERROR, 
        LINE_JOINING_EOF_ERROR // This is error token emitted, there is line continuation just before EOF.
                                // Line continuation is in the hidden channel, so normally is not emitted.
    }

@lexer::members {
  // new version with semantic actions in parser

  private static class Indent {
    public final int indent;
    public final int altindent;
    public static final Indent EMPTY = new Indent(0, 0);

    public Indent(int indent, int altindent) {
      this.indent = indent;
      this.altindent = altindent;
    }
  }

  // A queue where extra tokens are pushed on (see the NEWLINE lexer rule).
  private java.util.LinkedList<Token> tokens = new java.util.LinkedList<>();
  // The stack that keeps track of the indentation level.
  private java.util.Stack<Indent> indents = new java.util.Stack<>();
  // The amount of opened braces, brackets and parenthesis.
  private int opened = 0;
  // The most recently produced token.
  private Token lastToken = null;
  // wether we have expanded EOF to include necessary DEDENTS and a NEWLINE
  private boolean expandedEOF = false;

  private boolean longQuote1 = false; // """
  private boolean longQuote2 = false; // '''

  @Override
  public void emit(Token t) {
    super.setToken(t);
    tokens.offer(t);
  }

  int codePointDelta = 0; // keeps the lenght of code points that have more chars that one. 

  @Override
  public Token nextToken() {
    Token next = super.nextToken();
    // Check if the end-of-file is ahead to insert any missing DEDENTS and a NEWLINE.
    if (next.getType() == EOF && !expandedEOF) {
      expandedEOF = true;

      // Remove any trailing EOF tokens from our buffer.
      for (int i = tokens.size() - 1; i >= 0; i--) {
        if (tokens.get(i).getType() == EOF) {
          tokens.remove(i);
        }
      }

      // First emit an extra line break that serves as the end of the statement.
      this.emit(commonToken(Python3Parser.NEWLINE, "\n"));

      // Now emit as much DEDENT tokens as needed.
      while (!indents.isEmpty()) {
        this.emit(createDedent());
        indents.pop();
      }

      // Put the EOF back on the token stream.
      this.emit(commonToken(Python3Parser.EOF, "<EOF>"));
    }

    if (next.getChannel() == Token.DEFAULT_CHANNEL) {
      // Keep track of the last token on the default channel.
      this.lastToken = next;
    }

    Token result = tokens.isEmpty() ? next : tokens.poll();
    // The code below handle situation, when in the source code is used unicode of various length (bigger then XFFFF)
    // Lexer works with CodePointStream (antlr class) that is able to count positions 
    // of the text with such codepoints. For example if there is code:
    // `a = '𝔘𝔫𝔦𝔠𝔬𝔡𝔢'`
    // then the range of the string literal (from CodePoiintStream) is [4, 13],
    // but the java string substring(4,13)  returns `'𝔘𝔫𝔦𝔠`, because every
    // the letter is represented with code point of lenght 2. The problem is 
    // that we don't work in python implementation with code point stream, 
    // but just with the strings. So the lexer converts the ranges of tokens 
    // to the "string" representation. 
    if (result.getType() != Python3Parser.EOF) {
        // get the text from CodePointStream
        String text = result.getText();
        int len = text.length();
        int delta = 0;
        // find if there is code point and how big is
        if (result.getType() != NEWLINE) {
            // we don't have to check the new line token
            for (int i = 0; i < len; i++) {
                // check if there is a codepoint with char count bigger then 1
                int codePoint = Character.codePointAt(text, i);
                delta += Character.charCount(codePoint);
            }
            delta = delta - len;
        }
        if (codePointDelta > 0 || delta > 0) { // until codePointDelta is zerou, we don't have to change the tokens. 
            CommonToken ctoken = (CommonToken) result;
            if (delta > 0) {
                // the token text contains a code point with more chars than 1
                if (codePointDelta > 0) {
                    // set the new start of the token, if this is not the first token with a code point
                    ctoken.setStartIndex(ctoken.getStartIndex() + codePointDelta);
                }
                // increse the codePointDelta with the char length of the code points in the text
                codePointDelta += delta;
            } else {
                // we have to shift all the tokens if there is a code point with more bytes. 
                ctoken.setStartIndex(result.getStartIndex() + codePointDelta);
            }
            // correct the end offset
            ctoken.setStopIndex(ctoken.getStopIndex() + codePointDelta);
            // TODO this is not nice. This expect the current implementation
            // of CommonToken, is done in the way that reads the text from CodePointStream until
            // the private field text is not set. 
            ctoken.setText(text);
        }
    }
    return result;
  }

  public boolean isOpened() {
	  return this.opened > 0 || this.longQuote1 || this.longQuote2;
  }

  private void usedQuote1() {
	if (!this.longQuote2){
		this.longQuote1 = !this.longQuote1;
	}
  }

  private void usedQuote2() {
	if (!this.longQuote1){
		this.longQuote2 = !this.longQuote2;
	}
  }

  private Token createDedent() {
    CommonToken dedent = commonToken(Python3Parser.DEDENT, "");
    dedent.setLine(this.lastToken.getLine());
    return dedent;
  }

  private Token createLineContinuationEOFError() {
    // this has to be called only at the end of the file
    CommonToken errorToken = commonToken(Python3Parser.LINE_JOINING_EOF_ERROR, "");
    // set the position at the previous char, which has to be line continuation
    errorToken.setStartIndex(_input.index() - 1);
    errorToken.setStopIndex(_input.index() - 1);
    return errorToken;
  }
  
  private Token createIndentError(int type) {
    // For some reason, CPython sets the error position to the end of line
    int cur = getCharIndex();
    String s;
    do {
        s = _input.getText(new Interval(cur, cur));
        cur++;
    } while (!s.isEmpty() && s.charAt(0) != '\n');
    cur--;
    CommonToken error = new CommonToken(this._tokenFactorySourcePair, type, DEFAULT_TOKEN_CHANNEL, cur, cur);
    error.setLine(this.lastToken.getLine());
    return error;
  }

  private CommonToken commonToken(int type, String text) {
    int stop = Math.max(this.getCharIndex() - 1, 0);
    int start = Math.max(text.isEmpty() ? stop : stop - text.length() + 1, 0);
    return new CommonToken(this._tokenFactorySourcePair, type, DEFAULT_TOKEN_CHANNEL, start, stop);
  }

  // Calculates the indentation of the provided spaces, taking the
  // following rules into account:
  //
  // "Tabs are replaced (from left to right) by one to eight spaces
  //  such that the total number of characters up to and including
  //  the replacement is a multiple of eight [...]"
  //
  // Altindent is an alternative measure of spaces where tabs are
  // counted as one space. The purpose is to validate that the code
  // doesn't mix tabs and spaces in inconsistent way.
  //
  //  -- https://docs.python.org/3.1/reference/lexical_analysis.html#indentation
  static Indent getIndentationCount(String spaces) {
    int indent = 0;
    int altindent = 0;
    for (char ch : spaces.toCharArray()) {
      switch (ch) {
        case '\r':
        case '\n':
        case '\f':
          // ignore
          break;
        case '\t':
          indent += 8 - (indent % 8);
          altindent++;
          break;
        default:
          // A normal space char.
          indent++;
          altindent++;
      }
    }

    return new Indent(indent, altindent);
  }

  boolean atStartOfInput() {
    return super.getCharPositionInLine() == 0 && super.getLine() == 1;
  }
}

@parser::header
{
import com.oracle.graal.python.builtins.objects.PEllipsis;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic;
import com.oracle.graal.python.nodes.expression.ExpressionNode;
import com.oracle.graal.python.nodes.expression.UnaryArithmetic;
import com.oracle.graal.python.nodes.statement.ExceptNode;
import com.oracle.graal.python.nodes.statement.StatementNode;
import com.oracle.graal.python.parser.PythonSSTNodeFactory;
import com.oracle.graal.python.parser.ScopeEnvironment;
import com.oracle.graal.python.parser.ScopeInfo;
import com.oracle.graal.python.nodes.EmptyNode;
import com.oracle.graal.python.nodes.PNode;
import com.oracle.graal.python.nodes.frame.ReadNode;
import com.oracle.graal.python.parser.sst.*;

import com.oracle.graal.python.parser.sst.SSTNode;

import com.oracle.truffle.api.frame.FrameDescriptor;

import java.util.Arrays;
}


@parser::members {
    private static class LoopState {
        public boolean containsBreak;
        public boolean containsContinue;
    }
	private PythonSSTNodeFactory factory;
	private ScopeEnvironment scopeEnvironment;
	private LoopState loopState;

	public final LoopState startLoop() {
	    try {
	        return loopState;
	    } finally {
	        loopState = new LoopState();
	    }
	}
	
	public final LoopState saveLoopState() {
		try {
	        return loopState;
	    } finally {
	        loopState = null;
	    }
	}
	
	private Object[] stack = new Object[8];
	private int stackIndex;
	
	public final int start() {
		return stackIndex;
	}
	
	public final void push(Object value) {
		if (stackIndex >= stack.length) {
			stack = Arrays.copyOf(stack, stack.length * 2);
		}
		stack[stackIndex++] = value;
	}
	
	public final Object[] getArray(int start) {
		try {
			return Arrays.copyOfRange(stack, start, stackIndex);
		} finally {
			stackIndex = start;
		}
	}

	public final <T> T[] getArray(int start, Class<? extends T[]> clazz) {
		try {
			return Arrays.copyOfRange(stack, start, stackIndex, clazz);
		} finally {
			stackIndex = start;
		}
	}

	private String[] stringStack = new String[8];
	private int stringStackIndex;
	
	public final int stringStart() {
		return stringStackIndex;
	}
	
	public final void pushString(String value) {
		if (stringStackIndex >= stringStack.length) {
			stringStack = Arrays.copyOf(stringStack, stringStack.length * 2);
		}
		stringStack[stringStackIndex++] = value;
	}
	
	public final String[] getStringArray(int start) {
		try {
			return Arrays.copyOfRange(stringStack, start, stringStackIndex);
		} finally {
			stringStackIndex = start;
		}
	}
    

        public void setFactory(PythonSSTNodeFactory factory) {
            this.factory = factory;
            scopeEnvironment = factory.getScopeEnvironment();
        }

    private static class PythonRecognitionException extends RecognitionException{
        static final long serialVersionUID = 1L;
            
        public PythonRecognitionException(String message, Recognizer<?, ?> recognizer, IntStream input, ParserRuleContext ctx, Token offendingToken) {
            super(message, recognizer, input, ctx);
            setOffendingToken(offendingToken);
        }

    }

    private int getStartIndex(RuleNode node) { 
        return ((ParserRuleContext) node).getStart().getStartIndex();
    }

    private int getStartIndex(Token token) {
        return token.getStartIndex();
    }

    private int getStopIndex(RuleNode node) {
        // add 1 to fit truffle source sections
        return ((ParserRuleContext) node).getStop().getStopIndex() + 1;
    }

    private int getStopIndex(Token token) {
        int stopIndex;
        if (token.getType() != NEWLINE) {
            stopIndex = token.getStopIndex();
        } else {
            // We don't have to have new lines in the source section
            int tokenIndex = token.getTokenIndex();
            Token tmp = token;
            while(tmp.getType() == NEWLINE && tokenIndex > 0) {
                tmp = getTokenStream().get(--tokenIndex);
            }
            stopIndex = tmp.getStopIndex();
        }
        // add 1 to fit truffle source sections
        return stopIndex + 1;
    }

    /** Get the last offset of the context */
    private int getLastIndex(ParserRuleContext ctx) {
    	// ignores ctx
        return getStopIndex(this._input.get(this._input.index() - 1));
    }
}

/*
 * parser rules
 */

single_input [boolean interactive, FrameDescriptor curInlineLocals] returns [ SSTNode result ]
locals
[ com.oracle.graal.python.parser.ScopeInfo scope, ArrayList<StatementNode> list ]
:
	{
	    if (!$interactive && $curInlineLocals != null) {
                ScopeInfo functionScope = scopeEnvironment.pushScope("<single_input>", ScopeInfo.ScopeKind.Function, $curInlineLocals);
                functionScope.setHasAnnotations(true);
            } else {
                scopeEnvironment.pushScope(_localctx.toString(), ScopeInfo.ScopeKind.Module);
            }
	}
	{ loopState = null; }
	{ int start = start(); }
	BOM?
	(
		NEWLINE
		| simple_stmt
		| compound_stmt
	) NEWLINE* EOF
	{ $result = new BlockSSTNode(getArray(start, SSTNode[].class), getStartIndex($ctx),  getLastIndex($ctx)); }
	{
            if ($interactive || $curInlineLocals != null) {
               scopeEnvironment.popScope();
            }
	}
;

file_input returns [ SSTNode result ]
locals
[ com.oracle.graal.python.parser.ScopeInfo scope, ArrayList<StatementNode> list ]
:
	{  _localctx.scope = scopeEnvironment.pushScope(_localctx.toString(), ScopeInfo.ScopeKind.Module); }
	{ loopState = null; }
	{ int start = start(); }
	BOM?
	(
		NEWLINE
		| stmt
	)* EOF
	{ 
            Token stopToken = $stmt.stop;
            $result = new BlockSSTNode(getArray(start, SSTNode[].class), getStartIndex($ctx), 
                stopToken != null ?  getStopIndex(stopToken) : getLastIndex($ctx)); }
	{ 
           scopeEnvironment.popScope(); 
        }
;

withArguments_input [boolean interactive, FrameDescriptor curInlineLocals] returns [ SSTNode result ]
locals
[ com.oracle.graal.python.parser.ScopeInfo scope, ArrayList<StatementNode> list ]
:
	{
            ScopeInfo functionScope = scopeEnvironment.pushScope("<withArguments_input>", ScopeInfo.ScopeKind.Function, $curInlineLocals);
            functionScope.setHasAnnotations(true);
	    
	}
	{ loopState = null; }
	{ int start = start(); }
	BOM?
	(
		NEWLINE
		| stmt
	)* EOF
	{ $result = new BlockSSTNode(getArray(start, SSTNode[].class), getStartIndex($ctx),  getLastIndex($ctx)); }
	{
            scopeEnvironment.popScope();
	}
;

eval_input returns [SSTNode result]
locals [ com.oracle.graal.python.parser.ScopeInfo scope ]
:
	{ scopeEnvironment.pushScope(_localctx.toString(), ScopeInfo.ScopeKind.Module); }
	BOM? testlist NEWLINE* EOF
	{ $result = $testlist.result; }
	{scopeEnvironment.popScope(); }
;

decorator:
    { ArgListBuilder args = null ;}
    '@' dotted_name ( '(' arglist ')' {args = $arglist.result; })? NEWLINE
    {   
        String dottedName = $dotted_name.result;
        if (dottedName.contains(".")) {
            factory.getScopeEnvironment().addSeenVar(dottedName.split("\\.")[0]);
        } else {
            factory.getScopeEnvironment().addSeenVar(dottedName);
        }
        push( new DecoratorSSTNode(dottedName, args, getStartIndex($ctx), getLastIndex($ctx))); 
    }
;

decorators returns [DecoratorSSTNode[] result]: 
    {int start = start();}
    decorator+
    {$result = getArray(start, DecoratorSSTNode[].class);}
;

decorated: 
    { SSTNode decor; }
    decorators 
        (
            classdef | 
            funcdef | 
            async_funcdef 
        )
    { stack[stackIndex-1] = new DecoratedSSTNode($decorators.result, (SSTNode)stack[stackIndex-1], getStartIndex($ctx), getLastIndex($ctx)); }
;

async_funcdef: ASYNC funcdef;
funcdef
:
	'def' n=NAME parameters
	(
		'->' test
	)? ':' 
	{ 
            String name = $n.getText(); 
            ScopeInfo enclosingScope = scopeEnvironment.getCurrentScope();
            String enclosingClassName = enclosingScope.isInClassScope() ? enclosingScope.getScopeId() : null;
            ScopeInfo functionScope = scopeEnvironment.pushScope(name, ScopeInfo.ScopeKind.Function);
            LoopState savedLoopState = saveLoopState();
            functionScope.setHasAnnotations(true);
            $parameters.result.defineParamsInScope(functionScope); 
        }
	s = suite
	{ 
            SSTNode funcDef = new FunctionDefSSTNode(scopeEnvironment.getCurrentScope(), name, enclosingClassName, $parameters.result, $s.result, getStartIndex(_localctx), getStopIndex(((FuncdefContext)_localctx).s));
            scopeEnvironment.popScope();
            loopState = savedLoopState;
            push(funcDef);
        }
;

parameters returns [ArgDefListBuilder result]
:       { ArgDefListBuilder args = new ArgDefListBuilder(); }
	'(' typedargslist[args]? ')'
        { $result = args; }
;

typedargslist [ArgDefListBuilder args]
:
    (
        defparameter[args] ( ',' defparameter[args] )* ',' '/' {args.markPositionalOnlyIndex();}
            (',' defparameter[args] ( ',' defparameter[args] )*)?
                ( ',' 
                    ( splatparameter[args]	
                        ( ',' defparameter[args])*
                        ( ',' ( kwargsparameter[args] ','? )? )?
                        | kwargsparameter[args] ','?
                    )?
                )?
	| defparameter[args] ( ',' defparameter[args] )*
            ( ',' 
                ( splatparameter[args]	
                    ( ',' defparameter[args])*
                    ( ',' ( kwargsparameter[args] ','? )?)?
                    | kwargsparameter[args] ','?
		)?
            )?
	| splatparameter[args]
            ( ',' defparameter[args])*
            ( ',' ( kwargsparameter[args] ','? )? )?
	| kwargsparameter[args] ','?
    )
    {
        if (!args.validateArgumentsAfterSplat()) {
            throw new PythonRecognitionException("named arguments must follow bare *", this, _input, $ctx, getCurrentToken());
        }
    }
;
  
defparameter [ArgDefListBuilder args]
:
	NAME
	{ SSTNode type = null; SSTNode defValue = null; }
	( ':' test { type = $test.result; } )?
	( '=' test { defValue = $test.result; } )?
	{ 
            ArgDefListBuilder.AddParamResult result = args.addParam($NAME.text, type, defValue); 
            switch(result) {
                case NONDEFAULT_FOLLOWS_DEFAULT:
                    throw new PythonRecognitionException("non-default argument follows default argument", this, _input, $ctx, getCurrentToken());
                case DUPLICATED_ARGUMENT:
                    throw new PythonRecognitionException("duplicate argument '" + $NAME.text + "' in function definition", this, _input, $ctx, getCurrentToken());
            } 
            
        }
;

splatparameter [ArgDefListBuilder args]
:
	'*'
	{ String name = null; SSTNode type = null; }
	(
		NAME { name = $NAME.text; }
		( ':' test { type = $test.result; } )?
	)?
	{ args.addSplat(name, type); }
;

kwargsparameter [ArgDefListBuilder args]
:
	'**' NAME
	{ SSTNode type = null; }
	( ':' test { type = $test.result; } )?
	{ args.addKwargs($NAME.text, type); }
;

varargslist returns [ArgDefListBuilder result]
:
	{ ArgDefListBuilder args = new ArgDefListBuilder(); }
	(
            vdefparameter[args] ( ',' vdefparameter[args] )* ',' '/' {args.markPositionalOnlyIndex();}
                (',' vdefparameter[args] (',' vdefparameter[args])* )?
                    ( ','
                        ( vsplatparameter[args]
                            ( ',' vdefparameter[args])*
                            ( ',' (vkwargsparameter[args] ','? )? )?
                            | vkwargsparameter[args] ','?
                        )?
                    )?
            | vdefparameter[args] (',' vdefparameter[args])*
                ( ','
                    ( vsplatparameter[args]
                        ( ',' vdefparameter[args])*
                        ( ',' (vkwargsparameter[args] ','? )? )?
                        | vkwargsparameter[args] ','?
                    )?
                )?
            | vsplatparameter[args]
                (',' vdefparameter[args])*
                ( ',' (vkwargsparameter[args] ','? )? )?
            | vkwargsparameter[args] ','?
	)
    {
        if (!args.validateArgumentsAfterSplat()) {
            throw new PythonRecognitionException("named arguments must follow bare *", this, _input, $ctx, getCurrentToken());
        }
    }
	{ $result = args; }
;

vdefparameter [ArgDefListBuilder args]
:
	NAME
	{ SSTNode defValue = null; }
	( '=' test { defValue = $test.result; } )?
	{ 
            ArgDefListBuilder.AddParamResult result = args.addParam($NAME.text, null, defValue); 
            switch(result) {
                case NONDEFAULT_FOLLOWS_DEFAULT:
                    throw new PythonRecognitionException("non-default argument follows default argument", this, _input, $ctx, getCurrentToken());
                case DUPLICATED_ARGUMENT:
                    throw new PythonRecognitionException("duplicate argument '" + $NAME.text + "' in function definition", this, _input, $ctx, getCurrentToken());
            }
            
        }
;

vsplatparameter [ArgDefListBuilder args]
:
	'*'
	{ String name = null; }
	( NAME { name = $NAME.text; } )?
	{ args.addSplat(name, null);}
;

vkwargsparameter [ArgDefListBuilder args]
:
	'**' NAME
	{args.addKwargs($NAME.text, null);}
;

stmt
:
	simple_stmt | compound_stmt
;

simple_stmt
:
	small_stmt ( ';' small_stmt )* ';'? NEWLINE
;

small_stmt
:
	expr_stmt
	| del_stmt
	| p='pass'
            { 
                int start = $p.getStartIndex(); 
                push(new SimpleSSTNode(SimpleSSTNode.Type.PASS, start, start + 4 ));
            }
	| flow_stmt
	| import_stmt
	| global_stmt
	| nonlocal_stmt
	| assert_stmt
;

expr_stmt
:       lhs=testlist_star_expr
	{ SSTNode rhs = null; 
          int rhsStopIndex = 0;
        }
	(
		':' t=test
		(
			'=' test { rhs = $test.result;}
		)?
		{ 
                    rhsStopIndex = getStopIndex($test.stop);
                    if (rhs == null) {
                        rhs = new SimpleSSTNode(SimpleSSTNode.Type.NONE,  -1, -1);
                    }
                    push(factory.createAnnAssignment($lhs.result, $t.result, rhs, getStartIndex($ctx), rhsStopIndex)); 
                }
		|
		augassign
		(
			yield_expr { rhs = $yield_expr.result; rhsStopIndex = getStopIndex($yield_expr.stop);}
			|
			testlist { rhs = $testlist.result; rhsStopIndex = getStopIndex($testlist.stop);}
		)
		{ push(factory.createAugAssignment($lhs.result, $augassign.text, rhs, getStartIndex($ctx), rhsStopIndex));}
		|
		{ int start = start(); }
		{ SSTNode value = $lhs.result; }
		(
			'='
			{ push(value); }
			(
				yield_expr { value = $yield_expr.result; rhsStopIndex = getStopIndex($yield_expr.stop); }
				|
				testlist_star_expr { value = $testlist_star_expr.result; rhsStopIndex = getStopIndex($testlist_star_expr.stop);}
			)
		)*
		{ 
                    if (value instanceof StarSSTNode) {
                        throw new PythonRecognitionException("can't use starred expression here", this, _input, $ctx, $ctx.start);
                    }
                    if (start == start()) {
                        push(new ExpressionStatementSSTNode(value));
                    } else {
                        SSTNode[] lhs = getArray(start, SSTNode[].class);
                        if (lhs.length == 1 && lhs[0] instanceof StarSSTNode) {
                            throw new PythonRecognitionException("starred assignment target must be in a list or tuple", this, _input, $ctx, $ctx.start);
                        }
                        push(factory.createAssignment(lhs, value, getStartIndex(_localctx), rhsStopIndex));
                    }
                }
	)
;

testlist_star_expr returns [SSTNode result]
:
	
	(
		test { $result = $test.result; }
		| star_expr {  $result = $star_expr.result; }
	)
	(
                { 
                    int start = start(); 
                    push($result);
                }
		','
		(
			(
				test { push($test.result); }
				| star_expr { push($star_expr.result); }
			)
			(
				','
				(
					test { push($test.result); }
					| star_expr { push($star_expr.result); }
				)
			)*
			','?
		)?
		{ $result = new CollectionSSTNode(getArray(start, SSTNode[].class), PythonBuiltinClassType.PTuple, getStartIndex($ctx), getLastIndex($ctx)); }
	)?
;

augassign: ('+=' | '-=' | '*=' | '@=' | '/=' | '%=' | '&=' | '|=' | '^=' | '<<=' | '>>=' | '**=' | '//=');
// For normal and annotated assignments, additional restrictions enforced by the interpreter

del_stmt
:
	'del' exprlist
	{ push(new DelSSTNode($exprlist.result, getStartIndex($ctx), getStopIndex($exprlist.stop))); }
;

flow_stmt
:
	b='break' 
	{
            if (loopState == null) {
                throw new PythonRecognitionException("'break' outside loop", this, _input, _localctx, $b);
            }
            push(new SimpleSSTNode(SimpleSSTNode.Type.BREAK, getStartIndex($b), getStopIndex($b)));
            loopState.containsBreak = true;
        }
	| c='continue'
	{
	        if (loopState == null) {
	            throw new PythonRecognitionException("'continue' not properly in loop", this, _input, _localctx, $c);
	        }
            push(new SimpleSSTNode(SimpleSSTNode.Type.CONTINUE, getStartIndex($c), getStopIndex($c)));
            loopState.containsContinue = true;
        }
	| return_stmt
	| raise_stmt
	| yield_stmt
;

return_stmt
:
	'return'
	{ SSTNode value = null; }
	( testlist_star_expr { value = $testlist_star_expr.result; } )?
	{ push(new ReturnSSTNode(value, getStartIndex($ctx), getLastIndex($ctx)));}
;

yield_stmt
:
	yield_expr
	{ push(new ExpressionStatementSSTNode($yield_expr.result)); }
;

raise_stmt
:
	{ SSTNode value = null; SSTNode from = null; }
	'raise'
	(
		test
		{ value = $test.result; }
		( 'from' test { from = $test.result; } )?
	)?
	{ push(new RaiseSSTNode(value, from, getStartIndex($ctx), getLastIndex($ctx))); }
; 

import_stmt
:
	import_name
	| import_from
;

import_name
:
	'import' dotted_as_names
;

import_from 
// note below: the ('.' | '...') is necessary because '...' is tokenized as ELLIPSIS
:
	'from'
	{ String name = ""; }
	(
		( '.' { name += '.'; } | '...' { name += "..."; } )* dotted_name { name += $dotted_name.result; }
		|
		( '.' { name += '.'; } | '...' { name += "..."; } )+
	)
	'import'
	{ String[][] asNames = null; }
	(
		'*'
		| '(' import_as_names { asNames = $import_as_names.result; } ')'
		| import_as_names { asNames = $import_as_names.result; } 
	)
	{ push(factory.createImportFrom(name, asNames, getStartIndex($ctx), getLastIndex($ctx))); }
;
              
import_as_name returns [String[] result] /* the first is name, the second as name */
:
	n=NAME
	{ String asName = null; }
	( 'as' NAME { asName = $NAME.text; } )?
	{ $result = new String[]{$n.text, asName}; }
;

import_as_names returns [String[][] result]
:
	{ int start = start(); }
	import_as_name { push($import_as_name.result); } ( ',' import_as_name { push($import_as_name.result); } )* ','?
	{ $result = getArray(start, String[][].class); }
;

dotted_name returns [String result]
:
	NAME { $result = $NAME.text; } ( '.' NAME { $result = $result + "." + $NAME.text; } )*
;
dotted_as_name
:
	dotted_name
	(
		'as' NAME 
		{ push(factory.createImport($dotted_name.result, $NAME.text, getStartIndex($ctx), getLastIndex($ctx)));}
		|
		{ push(factory.createImport($dotted_name.result, null, getStartIndex($ctx), getLastIndex($ctx)));}
	)
;
dotted_as_names
:
	dotted_as_name
	(
		',' dotted_as_name
	)*
;

global_stmt
:
	{ int start = stringStart(); }
	'global' NAME
	{ pushString($NAME.text); }
	(
		',' NAME
		{ pushString($NAME.text); }
	)*
	{ push(factory.registerGlobal(getStringArray(start), getStartIndex($ctx), getLastIndex($ctx))); }
;

nonlocal_stmt
:
	{ int start = stringStart(); }
	'nonlocal' NAME
	{ pushString($NAME.text); }
	(
		',' NAME
		{ pushString($NAME.text); }
	)*
	{ push(factory.registerNonLocal(getStringArray(start), getStartIndex($ctx), getLastIndex($ctx))); }
;

assert_stmt
:
	'assert' e=test
	{ SSTNode message = null; }
	(
		',' test
		{ message = $test.result; }
	)?
	{ push(new AssertSSTNode($e.result, message, getStartIndex($ctx), getLastIndex($ctx))); }
;

compound_stmt
:
	if_stmt 
	| while_stmt 
	| for_stmt
	| try_stmt
	| with_stmt
	| funcdef
	| classdef
	| decorated
	| async_stmt
;

async_stmt: ASYNC (funcdef | with_stmt | for_stmt);
if_stmt
:
	'if' if_test=test ':' if_suite=suite elif_stmt
	{ push(new IfSSTNode($if_test.result, $if_suite.result, $elif_stmt.result, getStartIndex($ctx), getStopIndex($elif_stmt.stop)));}
	
;
elif_stmt returns [SSTNode result]
:
	'elif' test ':' suite
	elif_stmt
	{ $result = new IfSSTNode($test.result, $suite.result, $elif_stmt.result, getStartIndex($ctx), getStopIndex(_localctx.elif_stmt.stop)); }
	|
	'else' ':' suite
	{ $result = $suite.result; }
	|
	{ $result = null; }
;

while_stmt
:
	'while' test ':' 
	{ LoopState savedState = startLoop(); }
	suite
	{ 
            WhileSSTNode result = new WhileSSTNode($test.result, $suite.result, loopState.containsContinue, loopState.containsBreak, getStartIndex($ctx),getStopIndex($suite.stop));
            loopState = savedState;
        }
	(
		'else' ':' suite 
                { 
                    result.setElse($suite.result); 
                    result.setEndOffset(getStopIndex($suite.stop));
                }
	)?
	{ 
            push(result);
        }
;

for_stmt
:
	'for' exprlist 'in' testlist ':'
	{ LoopState savedState = startLoop(); }
	suite
	{ 
            ForSSTNode result = factory.createForSSTNode($exprlist.result, $testlist.result, $suite.result, loopState.containsContinue, getStartIndex($ctx),getStopIndex($suite.stop));
            result.setContainsBreak(loopState.containsBreak);
            loopState = savedState;
        }
	(
		'else' ':' suite 
                { 
                    result.setElse($suite.result); 
                    result.setEndOffset(getStopIndex($suite.stop));
                }
	)?
	{  
            push(result);
        }
;

try_stmt
:
	'try' ':' body=suite
	{ int start = start(); }
	{ 
            SSTNode elseStatement = null; 
            SSTNode finallyStatement = null; 
        }
	(
		( except_clause { push($except_clause.result); } )+
		( 'else' ':' suite { elseStatement = $suite.result; } )?
		( 'finally' ':' suite { finallyStatement = $suite.result; } )? |
		  'finally' ':' suite { finallyStatement = $suite.result; }
	)
	{ push(new TrySSTNode($body.result, getArray(start, ExceptSSTNode[].class), elseStatement, finallyStatement, getStartIndex($ctx), getLastIndex($ctx))); }
;

except_clause returns [SSTNode result]
:
	'except'
	{ SSTNode testNode = null; String asName = null; }
	(
		test { testNode = $test.result; }
		( 'as' NAME 
                    { 
                        asName = $NAME.text; 
                        factory.getScopeEnvironment().createLocal(asName);
                    } 
                )?
	)?
	':'
	suite
	{ $result = new ExceptSSTNode(testNode, asName, $suite.result, getStartIndex($ctx), getStopIndex($suite.stop)); }
;

with_stmt
:
	'with' with_item
	{ 
            $with_item.result.setStartOffset(getStartIndex($ctx));
            push($with_item.result); 
        }
;

with_item returns [SSTNode result]
:
	test
	{ SSTNode asName = null; }
	( 'as' expr { asName = $expr.result; } )?
	{ SSTNode sub; }
	(
		',' with_item
		{ sub = $with_item.result; }
		| ':' suite
		{ sub = $suite.result; }
	)
	{ $result = factory.createWith($test.result, asName, sub, -1, getLastIndex($ctx)); }
;

// NB compile.c makes sure that the default except clause is last

suite returns [SSTNode result] locals [ArrayList<SSTNode> list]
:
	{ int start = start(); }
	(
		simple_stmt
		| NEWLINE INDENT stmt+ DEDENT
	)
	{ $result = new BlockSSTNode(getArray(start, SSTNode[].class));}
;

test returns [SSTNode result]
:
	or_test { $result = $or_test.result; }
	(
		'if' condition=or_test 'else' elTest=test
		{ $result = new TernaryIfSSTNode($condition.result, $result, $elTest.result, getStartIndex($ctx), getLastIndex($ctx));}
	)?
	| lambdef { $result = $lambdef.result; }
;

test_nocond returns [SSTNode result]
:
	or_test { $result = $or_test.result; }
	| lambdef_nocond { $result = $lambdef_nocond.result; }
;

lambdef returns [SSTNode result]
:
	
	l = 'lambda'
	{ ArgDefListBuilder args = null; }
	(
		varargslist { args = $varargslist.result; }
	)? 
        {
            ScopeInfo functionScope = scopeEnvironment.pushScope(ScopeEnvironment.LAMBDA_NAME, ScopeInfo.ScopeKind.Function); 
            functionScope.setHasAnnotations(true);
            if (args != null) {
                args.defineParamsInScope(functionScope);
            }
        }
	':'
	test
	{ scopeEnvironment.popScope(); }
	{ $result = new LambdaSSTNode(functionScope, args, $test.result, getStartIndex($ctx), getLastIndex($ctx)); }
;

lambdef_nocond returns [SSTNode result]
:
	l = 'lambda'
	{ ArgDefListBuilder args = null; }
	(
		varargslist { args = $varargslist.result;}
	)?
        {
            ScopeInfo functionScope = scopeEnvironment.pushScope(ScopeEnvironment.LAMBDA_NAME, ScopeInfo.ScopeKind.Function); 
            functionScope.setHasAnnotations(true);
            if (args != null) {
                args.defineParamsInScope(functionScope);
            }
        }
	':'
	test_nocond
	{ scopeEnvironment.popScope(); }
	{ $result = new LambdaSSTNode(functionScope, args, $test_nocond.result, getStartIndex($ctx), getLastIndex($ctx)); }
;

or_test returns [SSTNode result]
:
	first=and_test
	(
		{ int start = start(); }
		{ push($first.result); }
		( 'or' and_test { push($and_test.result); } )+
		{ $result = new OrSSTNode(getArray(start, SSTNode[].class), getStartIndex($ctx), getLastIndex($ctx)); }
		|
		{ $result = $first.result; }
	)
;

and_test returns [SSTNode result]
:
	first=not_test
	(
		{ int start = start(); }
		{ push($first.result); }
		( 'and' not_test { push($not_test.result); } )+
		{ $result = new AndSSTNode(getArray(start, SSTNode[].class), getStartIndex($ctx), getLastIndex($ctx)); }
		|
		{ $result = $first.result; }
	)
;

not_test returns [SSTNode result]
:
	'not' not_test
	{ $result = new NotSSTNode($not_test.result, getStartIndex($ctx), getLastIndex($ctx)); }
	| comparison
	{ $result = $comparison.result; }
;

comparison returns [SSTNode result]
:
	first=expr
	(
            { int start = start(); int stringStart = stringStart(); }
            ( comp_op expr { pushString($comp_op.result); push($expr.result); } )+
            { $result = new ComparisonSSTNode($first.result, getStringArray(stringStart), getArray(start, SSTNode[].class), getStartIndex($ctx), getStopIndex($expr.stop)); }
            |
            { $result = $first.result; }
	)
;

// <> isn't actually a valid comparison operator in Python. It's here for the
// sake of a __future__ import described in PEP 401 (which really works :-)
comp_op returns [String result]
:
	'<' { $result = "<"; }
	| '>' { $result = ">"; }
	| '==' { $result = "=="; }
	| '>=' { $result = ">="; }
	| '<=' { $result = "<="; }
	| '<>' { $result = "<>"; }
	| '!=' { $result = "!="; }
	| 'in' { $result = "in"; }
	| 'not' 'in' { $result = "notin"; }
	| 'is' { $result = "is"; }
	| 'is' 'not' { $result = "isnot"; }
;

star_expr returns [SSTNode result]: '*' expr { $result = new StarSSTNode($expr.result, getStartIndex($ctx), getLastIndex($ctx));};

expr returns [SSTNode result]
:
	xor_expr { $result = $xor_expr.result; }
	(
		'|' xor_expr { $result = new BinaryArithmeticSSTNode(BinaryArithmetic.Or, $result, $xor_expr.result, getStartIndex($ctx), getStopIndex($xor_expr.stop));}
	)*
;

xor_expr returns [SSTNode result]
:
	and_expr { $result = $and_expr.result; }
	(
		'^' and_expr { $result = new BinaryArithmeticSSTNode(BinaryArithmetic.Xor, $result, $and_expr.result, getStartIndex($ctx), getStopIndex($and_expr.stop)); }
	)*
;

and_expr returns [SSTNode result]
:
	shift_expr { $result = $shift_expr.result; }
	(
		'&' shift_expr { $result = new BinaryArithmeticSSTNode(BinaryArithmetic.And, $result, $shift_expr.result, getStartIndex($ctx), getStopIndex($shift_expr.stop)); }
	)*
;

shift_expr returns [SSTNode result]
:
	arith_expr { $result = $arith_expr.result; }
	(
		{ BinaryArithmetic arithmetic; }
		( '<<' { arithmetic = BinaryArithmetic.LShift; } | '>>' { arithmetic = BinaryArithmetic.RShift; } )
		arith_expr { $result = new BinaryArithmeticSSTNode(arithmetic, $result, $arith_expr.result, getStartIndex($ctx), getStopIndex($arith_expr.stop));}
	)*
;

arith_expr returns [SSTNode result]
:
	term { $result = $term.result; }
	(
		{ BinaryArithmetic arithmetic; }
		( '+' { arithmetic = BinaryArithmetic.Add; } | '-' { arithmetic = BinaryArithmetic.Sub; } )
		term { $result = new BinaryArithmeticSSTNode(arithmetic, $result, $term.result, getStartIndex($ctx), getStopIndex($term.stop)); }
	)*
;

term returns [SSTNode result]
:
	factor { $result = $factor.result; }
	(
		{ BinaryArithmetic arithmetic; }
		( '*' { arithmetic = BinaryArithmetic.Mul; } | '@' { arithmetic = BinaryArithmetic.MatMul; } | '/' { arithmetic = BinaryArithmetic.TrueDiv; } 
			| '%' { arithmetic = BinaryArithmetic.Mod; } | '//' { arithmetic = BinaryArithmetic.FloorDiv; } )
		factor { $result = new BinaryArithmeticSSTNode(arithmetic, $result, $factor.result, getStartIndex($ctx), getStopIndex($factor.stop)); }
	)*
;

factor returns [SSTNode result]
:
	{ 
            UnaryArithmetic arithmetic; 
            boolean isNeg = false;
        }
	( '+' { arithmetic = UnaryArithmetic.Pos; } | m='-' { arithmetic = UnaryArithmetic.Neg; isNeg = true; } | '~' { arithmetic = UnaryArithmetic.Invert; } )
	factor 
            { 
                assert _localctx.factor != null;
                SSTNode fResult = $factor.result;
                if (isNeg && fResult instanceof NumberLiteralSSTNode) {
                    if (((NumberLiteralSSTNode)fResult).isNegative()) {
                        // solving cases like --2
                        $result =  new UnarySSTNode(UnaryArithmetic.Neg, fResult, getStartIndex($ctx), getStopIndex($factor.stop)); 
                    } else {
                        ((NumberLiteralSSTNode)fResult).negate();
                        fResult.setStartOffset($m.getStartIndex());
                        $result =  fResult;
                    }
                } else {
                    $result = new UnarySSTNode(arithmetic, $factor.result, getStartIndex($ctx), getStopIndex($factor.stop)); 
                }
            }
	| power { $result = $power.result; }
;

power returns [SSTNode result]
:
	atom_expr { $result = $atom_expr.result; }
	(
		'**' factor { $result = new BinaryArithmeticSSTNode(BinaryArithmetic.Pow, $result, $factor.result, getStartIndex($ctx), getStopIndex($factor.stop)); }
	)?
;

atom_expr returns [SSTNode result]
:
	( AWAIT )? // 'await' is ignored for now 
	atom
	{ $result = $atom.result; }
	(
		'(' arglist CloseB=')' { $result = new CallSSTNode($result, $arglist.result, getStartIndex($ctx), $CloseB.getStopIndex() + 1);}
		| '[' subscriptlist c=']' { $result = new SubscriptSSTNode($result, $subscriptlist.result, getStartIndex($ctx), $c.getStopIndex() + 1);}
		| '.' NAME 
                {   
                    assert $NAME != null;
                    $result = new GetAttributeSSTNode($result, $NAME.text, getStartIndex($ctx), getStopIndex($NAME));
                }
	)*
;

atom returns [SSTNode result]
:
	'('
	(
		yield_expr
		{ $result = $yield_expr.result; }
		|
		setlisttuplemaker[PythonBuiltinClassType.PTuple, PythonBuiltinClassType.PGenerator]
		{ $result = $setlisttuplemaker.result; }
		|
		{ $result = new CollectionSSTNode(new SSTNode[0], PythonBuiltinClassType.PTuple, -1, -1); }
	)
	cp = ')' 
        {   
            if ($result instanceof CollectionSSTNode) {
                $result.setStartOffset(getStartIndex($ctx)); 
                $result.setEndOffset($cp.getStopIndex() + 1); 
            }
        }
	|
	startIndex = '['
        
	(
		setlisttuplemaker[PythonBuiltinClassType.PList, PythonBuiltinClassType.PList]
		{ $result = $setlisttuplemaker.result; }
		|
		{ $result = new CollectionSSTNode(new SSTNode[0], PythonBuiltinClassType.PList, -1, -1);}
	)
	endIndex = ']' 
        {
            if (!($result instanceof ForComprehensionSSTNode)) {
                $result.setStartOffset($startIndex.getStartIndex());
                $result.setEndOffset($endIndex.getStopIndex() + 1);
            }
        }
	|
	startIndex = '{'
	(
		dictmaker // dictmaker cannot be empty
		{ $result = $dictmaker.result; }
		|
		setlisttuplemaker[PythonBuiltinClassType.PSet, PythonBuiltinClassType.PSet]
		{ $result = $setlisttuplemaker.result; }
		|
		{ $result =  new CollectionSSTNode(new SSTNode[0], PythonBuiltinClassType.PDict, -1, -1);}
	)
	endIndex = '}' 
        {
            if (!($result instanceof ForComprehensionSSTNode)) {
                $result.setStartOffset($startIndex.getStartIndex());
                $result.setEndOffset($endIndex.getStopIndex() + 1);
            }
        }
	| NAME 
            {   
                String text = $NAME.text;
                $result = text != null ? factory.createVariableLookup(text,  $NAME.getStartIndex(), $NAME.getStopIndex() + 1) : null; 
            }
	| DECIMAL_INTEGER 
            { 
                String text = $DECIMAL_INTEGER.text;
                $result = text != null ? NumberLiteralSSTNode.create(text, 0, 10, $DECIMAL_INTEGER.getStartIndex(), $DECIMAL_INTEGER.getStopIndex() + 1) : null; 
            }
	| OCT_INTEGER 
            { 
                String text = $OCT_INTEGER.text;
                $result = text != null ? NumberLiteralSSTNode.create(text, 2, 8, $OCT_INTEGER.getStartIndex(), $OCT_INTEGER.getStopIndex() + 1) : null; 
            }
	| HEX_INTEGER 
            { 
                String text = $HEX_INTEGER.text;
                $result = text != null ? NumberLiteralSSTNode.create(text, 2, 16, $HEX_INTEGER.getStartIndex(), $HEX_INTEGER.getStopIndex() + 1) : null; 
            }
	| BIN_INTEGER 
            { 
                String text = $BIN_INTEGER.text;
                $result = text != null ? NumberLiteralSSTNode.create(text, 2, 2, $BIN_INTEGER.getStartIndex(), $BIN_INTEGER.getStopIndex() + 1) : null; 
            }
	| FLOAT_NUMBER 
            {   
                String text = $FLOAT_NUMBER.text;
                $result = text != null ? FloatLiteralSSTNode.create(text, false, $FLOAT_NUMBER.getStartIndex(), $FLOAT_NUMBER.getStopIndex() + 1) : null; 
            }
	| IMAG_NUMBER 
            { 
                String text = $IMAG_NUMBER.text;
                $result = text != null ? FloatLiteralSSTNode.create(text, true, $IMAG_NUMBER.getStartIndex(), $IMAG_NUMBER.getStopIndex() + 1) : null; 
            }
	| { int start = stringStart(); } ( STRING { pushString($STRING.text); } )+ { $result = factory.createStringLiteral(getStringArray(start), getStartIndex($ctx), getStopIndex($STRING)); }
	| t='...' { int start = $t.getStartIndex(); $result = new SimpleSSTNode(SimpleSSTNode.Type.ELLIPSIS,  start, start + 3);}
	| t='None' { int start = $t.getStartIndex(); $result = new SimpleSSTNode(SimpleSSTNode.Type.NONE,  start, start + 4);}
	| t='True' { int start = $t.getStartIndex(); $result = new BooleanLiteralSSTNode(true,  start, start + 4); }
	| t='False' { int start = $t.getStartIndex(); $result = new BooleanLiteralSSTNode(false, start, start + 5); }
;


subscriptlist returns [SSTNode result]
:
	subscript
	{ $result = $subscript.result; }
	(
		// a "," implies that a tuple should be created
		{ int start = start(); push($result); }
		','
		(
			subscript { push($subscript.result); }
			( ',' subscript { push($subscript.result); } )*
			','?
		)?
		{ $result = new CollectionSSTNode(getArray(start, SSTNode[].class), PythonBuiltinClassType.PTuple, getStartIndex($ctx), getLastIndex($ctx));}
	)?
;

subscript returns [SSTNode result]
:
	test
	{ $result = $test.result; }
	|
	{ SSTNode sliceStart = null; SSTNode sliceEnd = null; SSTNode sliceStep = null; }
	( test { sliceStart = $test.result; } )?
	':'
	( test { sliceEnd = $test.result; } )?
	(
		':'
		( test { sliceStep = $test.result; } )?
	)?
	{ $result = new SliceSSTNode(sliceStart, sliceEnd, sliceStep, getStartIndex($ctx), getLastIndex($ctx)); }
;

exprlist returns [SSTNode[] result]
:
	{ int start = start(); }
	(
		expr { push($expr.result); }
		| star_expr { push($star_expr.result); }
	)
	(
		','
		(
			expr { push($expr.result); }
			| star_expr { push($star_expr.result); }
		)
	)*
	(
		','
	)?
	{ $result = getArray(start, SSTNode[].class); }
;

testlist returns [SSTNode result]
:
	test
	{ $result = $test.result; }
	(
		// a "," implies that a tuple should be created
		{ int start = start(); push($result); }
		','
		(
			test { push($test.result); }
			( ',' test { push($test.result); } )*
			','?
		)?
		{ $result = new CollectionSSTNode(getArray(start, SSTNode[].class), PythonBuiltinClassType.PTuple, getStartIndex($ctx), getLastIndex($ctx));}
	)?
;

dictmaker returns [SSTNode result]
:
	
	(
            { 
                SSTNode value; 
                SSTNode name;
                ScopeInfo generator = scopeEnvironment.pushScope("generator", ScopeInfo.ScopeKind.DictComp);
                generator.setHasAnnotations(true);
                
            }
            (
		n=test ':' v=test
		{ name = $n.result; value = $v.result; }
		| '**' expr
		{ name = null; value = $expr.result; }
            )
            comp_for[value, name, PythonBuiltinClassType.PDict, 0]
            { 
                $result = $comp_for.result;
               scopeEnvironment.popScope();
            }
            
        )
        |
	(
            { 
                SSTNode value; 
                SSTNode name;
            }
            (
		n=test ':' v=test
		{ name = $n.result; value = $v.result; }
		| '**' expr
		{ name = null; value = $expr.result; }
            )
		
		{ int start = start(); push(name); push(value); }
		(
			','
			(
				n=test ':' v=test
				{ push($n.result); push($v.result); }
				| '**' expr
				{ push(null); push($expr.result); }
			)
		)*
		','?
		{ $result = new CollectionSSTNode(getArray(start, SSTNode[].class), PythonBuiltinClassType.PDict, -1, -1); }
	)
;

setlisttuplemaker [PythonBuiltinClassType type, PythonBuiltinClassType compType] returns [SSTNode result]
:
	
	(   { 
                SSTNode value; 
                ScopeInfo.ScopeKind scopeKind;
                switch (compType) {
                    case PList: scopeKind = ScopeInfo.ScopeKind.ListComp; break;
                    case PDict: scopeKind = ScopeInfo.ScopeKind.DictComp; break;
                    case PSet: scopeKind = ScopeInfo.ScopeKind.SetComp; break;
                    default: scopeKind = ScopeInfo.ScopeKind.GenExp;
                }
                ScopeInfo generator = scopeEnvironment.pushScope("generator", scopeKind); 
                generator.setHasAnnotations(true);
            }
            (
		test { value = $test.result; }
		|
		star_expr { value = $star_expr.result; }
            ) comp_for[value, null, $compType, 0]
            { 
                $result = $comp_for.result; 
                scopeEnvironment.popScope();
            }
	)
        |
	(   { SSTNode value; }
            (
		test { value = $test.result; }
		|
		star_expr { value = $star_expr.result; }
            )
		{ int start = start(); push(value); }
		(
			','
			(
				test { push($test.result); }
				|
				star_expr { push($star_expr.result); }
			)
		)*
		{ boolean comma = false; }
		(',' { comma = true; } )?
		{ 
                    SSTNode[] items = getArray(start, SSTNode[].class);
                    if ($type == PythonBuiltinClassType.PTuple && items.length == 1 && !comma) {
                        $result = items[0];
                    } else {
                        $result = new CollectionSSTNode(items, $type, -1, -1); 
                    }
                }
	)
;

classdef
locals [ com.oracle.graal.python.parser.ScopeInfo scope ]
:
	
	'class' NAME
	{ ArgListBuilder baseClasses = null; }
	( '(' arglist ')' { baseClasses = $arglist.result; } )?
        {
            // we need to create the scope here to resolve base classes in the outer scope
            factory.getScopeEnvironment().createLocal($NAME.text);
            ScopeInfo classScope = scopeEnvironment.pushScope($NAME.text, ScopeInfo.ScopeKind.Class); 
        }
    { LoopState savedLoopState = saveLoopState(); }
	':' suite
	{ push(factory.createClassDefinition($NAME.text, baseClasses, $suite.result, getStartIndex($ctx), getStopIndex($suite.stop))); }
	{ scopeEnvironment.popScope(); }
	{ loopState = savedLoopState; }
;

arglist returns [ArgListBuilder result]
:
	{ ArgListBuilder args = new ArgListBuilder(); }
	(
		argument[args]
		(
			',' argument[args]
		)*
		(
			','
		)?
	)?
	{ $result = args; }
;

// The reason that keywords are test nodes instead of NAME is that using NAME
// results in an ambiguity. ast.c makes sure it's a NAME.
// "test '=' test" is really "keyword '=' test", but we have no such token.
// These need to be in a single rule to avoid grammar that is ambiguous
// to our LL(1) parser. Even though 'test' includes '*expr' in star_expr,
// we explicitly match '*' here, too, to give it proper precedence.
// Illegal combinations and orderings are blocked in ast.c:
// multiple (test comp_for) arguments are blocked; keyword unpackings
// that precede iterable unpackings are blocked; etc.

argument [ArgListBuilder args] returns [SSTNode result]
:               {
                    ScopeInfo generator = scopeEnvironment.pushScope("generator", ScopeInfo.ScopeKind.GenExp); 
                    generator.setHasAnnotations(true);
                }
		test comp_for[$test.result, null, PythonBuiltinClassType.PGenerator, 0]
                {
                    args.addNakedForComp($comp_for.result);
                   scopeEnvironment.popScope();
                }
	|
                { String name = getCurrentToken().getText();
                  if (getCurrentToken().getType() != NAME) {
                    throw new PythonRecognitionException("keyword can't be an expression", this, _input, _localctx, getCurrentToken());
                  }
                  // TODO this is not nice. There is done two times lookup in collection to remove name from seen variables. !!!
                  boolean isNameAsVariableInScope = scopeEnvironment.getCurrentScope().getSeenVars() == null ? false : scopeEnvironment.getCurrentScope().getSeenVars().contains(name);
                }
		n=test 
                {
                    if (!((((ArgumentContext)_localctx).n).result instanceof VarLookupSSTNode)) {
                        throw new PythonRecognitionException("keyword can't be an expression", this, _input, _localctx, getCurrentToken());
                    }
                    if (!isNameAsVariableInScope && scopeEnvironment.getCurrentScope().getSeenVars().contains(name)) {
                        scopeEnvironment.getCurrentScope().getSeenVars().remove(name);
                    }
                } 
                    '=' test 
                    { 
                        args.addNamedArg(name, $test.result); 
                    }
	|
		test {  
                        if (args.hasNameArg()) {
                            throw new PythonRecognitionException("positional argument follows keyword argument", this, _input, _localctx, getCurrentToken());
                        }
                        if (args.hasKwArg()) {
                            throw new PythonRecognitionException("positional argument follows keyword argument unpacking", this, _input, _localctx, getCurrentToken());
                        }
                        args.addArg($test.result); 
                    }
	|
		'**' test { args.addKwArg($test.result); }
	|
		'*' test { 
                        if (args.hasKwArg()) {
                            throw new PythonRecognitionException("iterable argument unpacking follows keyword argument unpacking", this, _input, _localctx, getCurrentToken());
                        }
                        args.addStarArg($test.result); 
                    }
;

comp_for
[SSTNode target, SSTNode name, PythonBuiltinClassType resultType, int level]
returns [SSTNode result]
:
	{ 
            if (target instanceof StarSSTNode) {
                throw new PythonRecognitionException("iterable unpacking cannot be used in comprehension", this, _input, $ctx, $ctx.start);
            }
            boolean scopeCreated = true; 
            boolean async = false; 
        }
	(
		ASYNC { async = true; }
	)?
	{ 
            SSTNode iterator; 
            SSTNode[] variables;
            int lineNumber;
        }
	f = 'for' exprlist 'in' 
            {
                ScopeInfo currentScope = null;
                if (level == 0) {
                    currentScope = scopeEnvironment.getCurrentScope();
                    factory.getScopeEnvironment().setCurrentScope(currentScope.getParent());
                }
            }
                or_test    
	{   
            if (level == 0) {
                factory.getScopeEnvironment().setCurrentScope(currentScope);
            }
            lineNumber = $f.getLine();
            iterator = $or_test.result; 
            variables = $exprlist.result;
        }
	
	{ int start = start(); }
	(
		'if' test_nocond { push($test_nocond.result); }
	) *
	{ SSTNode[] conditions = getArray(start, SSTNode[].class); }
	
	(
            comp_for [iterator, null, PythonBuiltinClassType.PGenerator, level + 1]
            { 
                iterator = $comp_for.result; 
            }
	)?
	{ $result = factory.createForComprehension(async, $target, $name, variables, iterator, conditions, $resultType, lineNumber, level, getStartIndex($f), getLastIndex(_localctx)); }
;

// not used in grammar, but may appear in "node" passed from Parser to Compiler
encoding_decl: NAME;

yield_expr 
returns [SSTNode result] 
:   
    { 
        SSTNode value = null;
        boolean isFrom = false; 
    }
    'yield' 
        (
            'from' test {value = $test.result; isFrom = true;}
            |
            testlist_star_expr { value = $testlist_star_expr.result; }
        )?
        { $result = factory.createYieldExpressionSSTNode(value, isFrom, getStartIndex($ctx), getLastIndex($ctx)); }
;

/*
 * lexer rules
 */

STRING
 : STRING_LITERAL
 | BYTES_LITERAL
 ;

DEF : 'def';
RETURN : 'return';
RAISE : 'raise';
FROM : 'from';
IMPORT : 'import';
AS : 'as';
GLOBAL : 'global';
NONLOCAL : 'nonlocal';
ASSERT : 'assert';
IF : 'if';
ELIF : 'elif';
ELSE : 'else';
WHILE : 'while';
FOR : 'for';
IN : 'in';
TRY : 'try';
FINALLY : 'finally';
WITH : 'with';
EXCEPT : 'except';
LAMBDA : 'lambda';
OR : 'or';
AND : 'and';
NOT : 'not';
IS : 'is';
NONE : 'None';
TRUE : 'True';
FALSE : 'False';
CLASS : 'class';
YIELD : 'yield';
DEL : 'del';
PASS : 'pass';
CONTINUE : 'continue';
BREAK : 'break';
ASYNC : 'async';
AWAIT : 'await';

NEWLINE
 : ( // For performance reasons, rejecting input starting with indent is handled by a preprocessing step in PythonParserImpl
     // {atStartOfInput()}?   SPACES |
     ( '\r'? '\n' | '\r' | '\f' ) SPACES?
   )
   {
     int next = _input.LA(1);
     if (opened > 0 || next == '\r' || next == '\n' || next == '\f' || next == '#') {
       // If we're inside a list or on a blank line, ignore all indents, 
       // dedents and line breaks.
       skip();
     }
     else {
       emit(commonToken(NEWLINE, "\n"));
       Indent indent;
       if (next == EOF) {
         // don't add indents if we're going to finish
         indent = Indent.EMPTY;
       } else {
         indent = getIndentationCount(getText());
       }
       Indent previous = indents.isEmpty() ? Indent.EMPTY : indents.peek();
       if (indent.indent == previous.indent) {
         if (indent.altindent != previous.altindent) {
           this.emit(createIndentError(Python3Parser.TAB_ERROR));
         }
         // skip indents of the same size as the present indent-size
         skip();
       }
       else if (indent.indent > previous.indent) {
         if (indent.altindent <= previous.altindent) {
           this.emit(createIndentError(Python3Parser.TAB_ERROR));
         }
         indents.push(indent);
         emit(commonToken(Python3Parser.INDENT, ""));
       }
       else {
         // Possibly emit more than 1 DEDENT token.
         while (!indents.isEmpty() && indents.peek().indent > indent.indent) {
           this.emit(createDedent());
           indents.pop();
         }
         Indent expectedIndent = indents.isEmpty() ? Indent.EMPTY : indents.peek();
         if (expectedIndent.indent != indent.indent) {
           this.emit(createIndentError(Python3Parser.INDENT_ERROR));
         }
         if (expectedIndent.altindent != indent.altindent) {
           this.emit(createIndentError(Python3Parser.TAB_ERROR));
         }
       }
     }
   }
 ;

/// identifier   ::=  id_start id_continue*
NAME
 : ID_START ID_CONTINUE*
 ;

/// stringliteral   ::=  [stringprefix](shortstring | longstring)
/// stringprefix    ::=  "r" | "u" | "R" | "U" | "f" | "F"
///                      | "fr" | "Fr" | "fR" | "FR" | "rf" | "rF" | "Rf" | "RF"
STRING_LITERAL
 : ( [rR] | [uU] | [fF] | ( [fF] [rR] ) | ( [rR] [fF] ) )? ( SHORT_STRING | LONG_STRING )
 ;

/// bytesliteral   ::=  bytesprefix(shortbytes | longbytes)
/// bytesprefix    ::=  "b" | "B" | "br" | "Br" | "bR" | "BR" | "rb" | "rB" | "Rb" | "RB"
BYTES_LITERAL
 : ( [bB] | ( [bB] [rR] ) | ( [rR] [bB] ) ) ( SHORT_BYTES | LONG_BYTES )
 ;

/// decimalinteger 
DECIMAL_INTEGER
 : NON_ZERO_DIGIT DIGIT* ('_' DIGIT+)*
 | '0'+ ('_''0'+)*
 ;

/// octinteger    
OCT_INTEGER
 : '0' [oO] (OCT_DIGIT | ('_' OCT_DIGIT+))+
 ;

/// hexinteger
HEX_INTEGER
 : '0' [xX] (HEX_DIGIT | ('_' HEX_DIGIT+))+
 ;

/// bininteger
BIN_INTEGER
 : '0' [bB] (BIN_DIGIT | ('_' BIN_DIGIT+))+
 ;

/// floatnumber   ::=  pointfloat | exponentfloat
FLOAT_NUMBER
 : POINT_FLOAT
 | EXPONENT_FLOAT
 ;

/// imagnumber ::=  (floatnumber | intpart) ("j" | "J")
IMAG_NUMBER
 : ( FLOAT_NUMBER | INT_PART ) [jJ]
 ;

DOT : '.';
ELLIPSIS : '...';
STAR : '*';
OPEN_PAREN : '(' {opened++;};
CLOSE_PAREN : ')' {opened--;};
COMMA : ',';
COLON : ':';
SEMI_COLON : ';';
POWER : '**';
ASSIGN : '=';
OPEN_BRACK : '[' {opened++;};
CLOSE_BRACK : ']' {opened--;};
OR_OP : '|';
XOR : '^';
AND_OP : '&';
LEFT_SHIFT : '<<';
RIGHT_SHIFT : '>>';
ADD : '+';
MINUS : '-';
DIV : '/';
MOD : '%';
IDIV : '//';
NOT_OP : '~';
OPEN_BRACE : '{' {opened++;};
CLOSE_BRACE : '}' {opened--;};
LESS_THAN : '<';
GREATER_THAN : '>';
EQUALS : '==';
GT_EQ : '>=';
LT_EQ : '<=';
NOT_EQ_1 : '<>';
NOT_EQ_2 : '!=';
AT : '@';
ARROW : '->';
ADD_ASSIGN : '+=';
SUB_ASSIGN : '-=';
MULT_ASSIGN : '*=';
AT_ASSIGN : '@=';
DIV_ASSIGN : '/=';
MOD_ASSIGN : '%=';
AND_ASSIGN : '&=';
OR_ASSIGN : '|=';
XOR_ASSIGN : '^=';
LEFT_SHIFT_ASSIGN : '<<=';
RIGHT_SHIFT_ASSIGN : '>>=';
POWER_ASSIGN : '**=';
IDIV_ASSIGN : '//=';
LONG_QUOTES1 : '"""' {usedQuote1();};
LONG_QUOTES2 : '\'\'\'' {usedQuote2();};

SKIP_
 : ( SPACES 
| COMMENT 
| LINE_JOINING 
    {   
        // We need to hanle line continuation here, because normally is hidden for the parser
        // but we need to handle the case, where is just before EOF.
        if (_input.size() == _input.index()) {
            this.emit(createLineContinuationEOFError());
        }
    }
)  -> skip
 ;

BOM : '\uFEFF';

UNKNOWN_CHAR
 : . 
    {
        // check the case when the char is line continuation just before EOF
        if (_input.size() == _input.index() && "\\".equals(_input.getText(Interval.of(_input.size()- 1, _input.size())))) {
            this.emit(createLineContinuationEOFError());
        }
    }
 ;

/* 
 * fragments 
 */

/// shortstring     ::=  "'" shortstringitem* "'" | '"' shortstringitem* '"'
/// shortstringitem ::=  shortstringchar | stringescapeseq
/// shortstringchar ::=  <any source character except "\" or newline or the quote>
fragment SHORT_STRING
 : '\'' ( STRING_ESCAPE_SEQ | ~[\\\r\n\f'] )* '\''
 | '"' ( STRING_ESCAPE_SEQ | ~[\\\r\n\f"] )* '"'
 ;
/// longstring      ::=  "'''" longstringitem* "'''" | '"""' longstringitem* '"""'
fragment LONG_STRING
 : '\'\'\'' LONG_STRING_ITEM*? '\'\'\''
 | '"""' LONG_STRING_ITEM*? '"""'
 ;

/// longstringitem  ::=  longstringchar | stringescapeseq
fragment LONG_STRING_ITEM
 : LONG_STRING_CHAR
 | STRING_ESCAPE_SEQ
 ;

/// longstringchar  ::=  <any source character except "\">
fragment LONG_STRING_CHAR
 : ~'\\'
 ;

/// stringescapeseq ::=  "\" <any source character>
fragment STRING_ESCAPE_SEQ
 : '\\' .
 | '\\' NEWLINE
 ;

/// nonzerodigit   ::=  "1"..."9"
fragment NON_ZERO_DIGIT
 : [1-9]
 ;

/// digit          ::=  "0"..."9"
fragment DIGIT
 : [0-9]
 ;

/// octdigit       ::=  "0"..."7"
fragment OCT_DIGIT
 : [0-7]
 ;

/// hexdigit       ::=  digit | "a"..."f" | "A"..."F"
fragment HEX_DIGIT
 : [0-9a-fA-F]
 ;

/// bindigit       ::=  "0" | "1"
fragment BIN_DIGIT
 : [01]
 ;

/// pointfloat    ::=  [intpart] fraction | intpart "."
fragment POINT_FLOAT
 : INT_PART? FRACTION
 | INT_PART '.'
 ;

/// exponentfloat ::=  (intpart | pointfloat) exponent
fragment EXPONENT_FLOAT
 : ( INT_PART | POINT_FLOAT ) EXPONENT
 ;

/// intpart
fragment INT_PART
 : DIGIT+ ('_' DIGIT+)*
 ;

/// fraction
fragment FRACTION
 : '.' INT_PART
 ;

/// exponent      ::=  ("e" | "E") ["+" | "-"] digit+
fragment EXPONENT
 : [eE] [+-]? INT_PART
 ;

/// shortbytes     ::=  "'" shortbytesitem* "'" | '"' shortbytesitem* '"'
/// shortbytesitem ::=  shortbyteschar | bytesescapeseq
fragment SHORT_BYTES
 : '\'' ( SHORT_BYTES_CHAR_NO_SINGLE_QUOTE | BYTES_ESCAPE_SEQ )* '\''
 | '"' ( SHORT_BYTES_CHAR_NO_DOUBLE_QUOTE | BYTES_ESCAPE_SEQ )* '"'
 ;
    
/// longbytes      ::=  "'''" longbytesitem* "'''" | '"""' longbytesitem* '"""'
fragment LONG_BYTES
 : '\'\'\'' LONG_BYTES_ITEM*? '\'\'\''
 | '"""' LONG_BYTES_ITEM*? '"""'
 ;

/// longbytesitem  ::=  longbyteschar | bytesescapeseq
fragment LONG_BYTES_ITEM
 : LONG_BYTES_CHAR
 | BYTES_ESCAPE_SEQ
 ;

/// shortbyteschar ::=  <any ASCII character except "\" or newline or the quote>
fragment SHORT_BYTES_CHAR_NO_SINGLE_QUOTE
 : [\u0000-\u0009]
 | [\u000B-\u000C]
 | [\u000E-\u0026]
 | [\u0028-\u005B]
 | [\u005D-\u007F]
 ; 

fragment SHORT_BYTES_CHAR_NO_DOUBLE_QUOTE
 : [\u0000-\u0009]
 | [\u000B-\u000C]
 | [\u000E-\u0021]
 | [\u0023-\u005B]
 | [\u005D-\u007F]
 ; 

/// longbyteschar  ::=  <any ASCII character except "\">
fragment LONG_BYTES_CHAR
 : [\u0000-\u005B]
 | [\u005D-\u007F]
 ;

/// bytesescapeseq ::=  "\" <any ASCII character>
fragment BYTES_ESCAPE_SEQ
 : '\\' [\u0000-\u007F]
 ;

fragment SPACES
 : [ \t]+
 ;

fragment COMMENT
 : '#' ~[\r\n\f]*
 ;

fragment LINE_JOINING
 : '\\' SPACES? ( '\r'? '\n' | '\r' | '\f')
 ;

/// id_start     ::=  <all characters in general categories Lu, Ll, Lt, Lm, Lo, Nl, the underscore, and characters with the Other_ID_Start property>
fragment ID_START
 : '_'
 | [A-Z]
 | [a-z]
 | '\u00AA'
 | '\u00B5'
 | '\u00BA'
 | [\u00C0-\u00D6]
 | [\u00D8-\u00F6]
 | [\u00F8-\u01BA]
 | '\u01BB'
 | [\u01BC-\u01BF]
 | [\u01C0-\u01C3]
 | [\u01C4-\u0241]
 | [\u0250-\u02AF]
 | [\u02B0-\u02C1]
 | [\u02C6-\u02D1]
 | [\u02E0-\u02E4]
 | '\u02EE'
 | '\u037A'
 | '\u0386'
 | [\u0388-\u038A]
 | '\u038C'
 | [\u038E-\u03A1]
 | [\u03A3-\u03CE]
 | [\u03D0-\u03F5]
 | [\u03F7-\u0481]
 | [\u048A-\u04CE]
 | [\u04D0-\u04F9]
 | [\u0500-\u050F]
 | [\u0531-\u0556]
 | '\u0559'
 | [\u0561-\u0587]
 | [\u05D0-\u05EA]
 | [\u05F0-\u05F2]
 | [\u0621-\u063A]
 | '\u0640'
 | [\u0641-\u064A]
 | [\u066E-\u066F]
 | [\u0671-\u06D3]
 | '\u06D5'
 | [\u06E5-\u06E6]
 | [\u06EE-\u06EF]
 | [\u06FA-\u06FC]
 | '\u06FF'
 | '\u0710'
 | [\u0712-\u072F]
 | [\u074D-\u076D]
 | [\u0780-\u07A5]
 | '\u07B1'
 | [\u0904-\u0939]
 | '\u093D'
 | '\u0950'
 | [\u0958-\u0961]
 | '\u097D'
 | [\u0985-\u098C]
 | [\u098F-\u0990]
 | [\u0993-\u09A8]
 | [\u09AA-\u09B0]
 | '\u09B2'
 | [\u09B6-\u09B9]
 | '\u09BD'
 | '\u09CE'
 | [\u09DC-\u09DD]
 | [\u09DF-\u09E1]
 | [\u09F0-\u09F1]
 | [\u0A05-\u0A0A]
 | [\u0A0F-\u0A10]
 | [\u0A13-\u0A28]
 | [\u0A2A-\u0A30]
 | [\u0A32-\u0A33]
 | [\u0A35-\u0A36]
 | [\u0A38-\u0A39]
 | [\u0A59-\u0A5C]
 | '\u0A5E'
 | [\u0A72-\u0A74]
 | [\u0A85-\u0A8D]
 | [\u0A8F-\u0A91]
 | [\u0A93-\u0AA8]
 | [\u0AAA-\u0AB0]
 | [\u0AB2-\u0AB3]
 | [\u0AB5-\u0AB9]
 | '\u0ABD'
 | '\u0AD0'
 | [\u0AE0-\u0AE1]
 | [\u0B05-\u0B0C]
 | [\u0B0F-\u0B10]
 | [\u0B13-\u0B28]
 | [\u0B2A-\u0B30]
 | [\u0B32-\u0B33]
 | [\u0B35-\u0B39]
 | '\u0B3D'
 | [\u0B5C-\u0B5D]
 | [\u0B5F-\u0B61]
 | '\u0B71'
 | '\u0B83'
 | [\u0B85-\u0B8A]
 | [\u0B8E-\u0B90]
 | [\u0B92-\u0B95]
 | [\u0B99-\u0B9A]
 | '\u0B9C'
 | [\u0B9E-\u0B9F]
 | [\u0BA3-\u0BA4]
 | [\u0BA8-\u0BAA]
 | [\u0BAE-\u0BB9]
 | [\u0C05-\u0C0C]
 | [\u0C0E-\u0C10]
 | [\u0C12-\u0C28]
 | [\u0C2A-\u0C33]
 | [\u0C35-\u0C39]
 | [\u0C60-\u0C61]
 | [\u0C85-\u0C8C]
 | [\u0C8E-\u0C90]
 | [\u0C92-\u0CA8]
 | [\u0CAA-\u0CB3]
 | [\u0CB5-\u0CB9]
 | '\u0CBD'
 | '\u0CDE'
 | [\u0CE0-\u0CE1]
 | [\u0D05-\u0D0C]
 | [\u0D0E-\u0D10]
 | [\u0D12-\u0D28]
 | [\u0D2A-\u0D39]
 | [\u0D60-\u0D61]
 | [\u0D85-\u0D96]
 | [\u0D9A-\u0DB1]
 | [\u0DB3-\u0DBB]
 | '\u0DBD'
 | [\u0DC0-\u0DC6]
 | [\u0E01-\u0E30]
 | [\u0E32-\u0E33]
 | [\u0E40-\u0E45]
 | '\u0E46'
 | [\u0E81-\u0E82]
 | '\u0E84'
 | [\u0E87-\u0E88]
 | '\u0E8A'
 | '\u0E8D'
 | [\u0E94-\u0E97]
 | [\u0E99-\u0E9F]
 | [\u0EA1-\u0EA3]
 | '\u0EA5'
 | '\u0EA7'
 | [\u0EAA-\u0EAB]
 | [\u0EAD-\u0EB0]
 | [\u0EB2-\u0EB3]
 | '\u0EBD'
 | [\u0EC0-\u0EC4]
 | '\u0EC6'
 | [\u0EDC-\u0EDD]
 | '\u0F00'
 | [\u0F40-\u0F47]
 | [\u0F49-\u0F6A]
 | [\u0F88-\u0F8B]
 | [\u1000-\u1021]
 | [\u1023-\u1027]
 | [\u1029-\u102A]
 | [\u1050-\u1055]
 | [\u10A0-\u10C5]
 | [\u10D0-\u10FA]
 | '\u10FC'
 | [\u1100-\u1159]
 | [\u115F-\u11A2]
 | [\u11A8-\u11F9]
 | [\u1200-\u1248]
 | [\u124A-\u124D]
 | [\u1250-\u1256]
 | '\u1258'
 | [\u125A-\u125D]
 | [\u1260-\u1288]
 | [\u128A-\u128D]
 | [\u1290-\u12B0]
 | [\u12B2-\u12B5]
 | [\u12B8-\u12BE]
 | '\u12C0'
 | [\u12C2-\u12C5]
 | [\u12C8-\u12D6]
 | [\u12D8-\u1310]
 | [\u1312-\u1315]
 | [\u1318-\u135A]
 | [\u1380-\u138F]
 | [\u13A0-\u13F4]
 | [\u1401-\u166C]
 | [\u166F-\u1676]
 | [\u1681-\u169A]
 | [\u16A0-\u16EA]
 | [\u16EE-\u16F0]
 | [\u1700-\u170C]
 | [\u170E-\u1711]
 | [\u1720-\u1731]
 | [\u1740-\u1751]
 | [\u1760-\u176C]
 | [\u176E-\u1770]
 | [\u1780-\u17B3]
 | '\u17D7'
 | '\u17DC'
 | [\u1820-\u1842]
 | '\u1843'
 | [\u1844-\u1877]
 | [\u1880-\u18A8]
 | [\u1900-\u191C]
 | [\u1950-\u196D]
 | [\u1970-\u1974]
 | [\u1980-\u19A9]
 | [\u19C1-\u19C7]
 | [\u1A00-\u1A16]
 | [\u1D00-\u1D2B]
 | [\u1D2C-\u1D61]
 | [\u1D62-\u1D77]
 | '\u1D78'
 | [\u1D79-\u1D9A]
 | [\u1D9B-\u1DBF]
 | [\u1E00-\u1E9B]
 | [\u1EA0-\u1EF9]
 | [\u1F00-\u1F15]
 | [\u1F18-\u1F1D]
 | [\u1F20-\u1F45]
 | [\u1F48-\u1F4D]
 | [\u1F50-\u1F57]
 | '\u1F59'
 | '\u1F5B'
 | '\u1F5D'
 | [\u1F5F-\u1F7D]
 | [\u1F80-\u1FB4]
 | [\u1FB6-\u1FBC]
 | '\u1FBE'
 | [\u1FC2-\u1FC4]
 | [\u1FC6-\u1FCC]
 | [\u1FD0-\u1FD3]
 | [\u1FD6-\u1FDB]
 | [\u1FE0-\u1FEC]
 | [\u1FF2-\u1FF4]
 | [\u1FF6-\u1FFC]
 | '\u2071'
 | '\u207F'
 | [\u2090-\u2094]
 | '\u2102'
 | '\u2107'
 | [\u210A-\u2113]
 | '\u2115'
 | '\u2118'
 | [\u2119-\u211D]
 | '\u2124'
 | '\u2126'
 | '\u2128'
 | [\u212A-\u212D]
 | '\u212E'
 | [\u212F-\u2131]
 | [\u2133-\u2134]
 | [\u2135-\u2138]
 | '\u2139'
 | [\u213C-\u213F]
 | [\u2145-\u2149]
 | [\u2160-\u2183]
 | [\u2C00-\u2C2E]
 | [\u2C30-\u2C5E]
 | [\u2C80-\u2CE4]
 | [\u2D00-\u2D25]
 | [\u2D30-\u2D65]
 | '\u2D6F'
 | [\u2D80-\u2D96]
 | [\u2DA0-\u2DA6]
 | [\u2DA8-\u2DAE]
 | [\u2DB0-\u2DB6]
 | [\u2DB8-\u2DBE]
 | [\u2DC0-\u2DC6]
 | [\u2DC8-\u2DCE]
 | [\u2DD0-\u2DD6]
 | [\u2DD8-\u2DDE]
 | '\u3005'
 | '\u3006'
 | '\u3007'
 | [\u3021-\u3029]
 | [\u3031-\u3035]
 | [\u3038-\u303A]
 | '\u303B'
 | '\u303C'
 | [\u3041-\u3096]
 | [\u309B-\u309C]
 | [\u309D-\u309E]
 | '\u309F'
 | [\u30A1-\u30FA]
 | [\u30FC-\u30FE]
 | '\u30FF'
 | [\u3105-\u312C]
 | [\u3131-\u318E]
 | [\u31A0-\u31B7]
 | [\u31F0-\u31FF]
 | [\u3400-\u4DB5]
 | [\u4E00-\u9FBB]
 | [\uA000-\uA014]
 | '\uA015'
 | [\uA016-\uA48C]
 | [\uA800-\uA801]
 | [\uA803-\uA805]
 | [\uA807-\uA80A]
 | [\uA80C-\uA822]
 | [\uAC00-\uD7A3]
 | [\uF900-\uFA2D]
 | [\uFA30-\uFA6A]
 | [\uFA70-\uFAD9]
 | [\uFB00-\uFB06]
 | [\uFB13-\uFB17]
 | '\uFB1D'
 | [\uFB1F-\uFB28]
 | [\uFB2A-\uFB36]
 | [\uFB38-\uFB3C]
 | '\uFB3E'
 | [\uFB40-\uFB41]
 | [\uFB43-\uFB44]
 | [\uFB46-\uFBB1]
 | [\uFBD3-\uFD3D]
 | [\uFD50-\uFD8F]
 | [\uFD92-\uFDC7]
 | [\uFDF0-\uFDFB]
 | [\uFE70-\uFE74]
 | [\uFE76-\uFEFC]
 | [\uFF21-\uFF3A]
 | [\uFF41-\uFF5A]
 | [\uFF66-\uFF6F]
 | '\uFF70'
 | [\uFF71-\uFF9D]
 | [\uFF9E-\uFF9F]
 | [\uFFA0-\uFFBE]
 | [\uFFC2-\uFFC7]
 | [\uFFCA-\uFFCF]
 | [\uFFD2-\uFFD7]
 | [\uFFDA-\uFFDC]
 ;

/// id_continue  ::=  <all characters in id_start, plus characters in the categories Mn, Mc, Nd, Pc and others with the Other_ID_Continue property>
fragment ID_CONTINUE
 : ID_START
 | [0-9]
 | [\u0300-\u036F]
 | [\u0483-\u0486]
 | [\u0591-\u05B9]
 | [\u05BB-\u05BD]
 | '\u05BF'
 | [\u05C1-\u05C2]
 | [\u05C4-\u05C5]
 | '\u05C7'
 | [\u0610-\u0615]
 | [\u064B-\u065E]
 | [\u0660-\u0669]
 | '\u0670'
 | [\u06D6-\u06DC]
 | [\u06DF-\u06E4]
 | [\u06E7-\u06E8]
 | [\u06EA-\u06ED]
 | [\u06F0-\u06F9]
 | '\u0711'
 | [\u0730-\u074A]
 | [\u07A6-\u07B0]
 | [\u0901-\u0902]
 | '\u0903'
 | '\u093C'
 | [\u093E-\u0940]
 | [\u0941-\u0948]
 | [\u0949-\u094C]
 | '\u094D'
 | [\u0951-\u0954]
 | [\u0962-\u0963]
 | [\u0966-\u096F]
 | '\u0981'
 | [\u0982-\u0983]
 | '\u09BC'
 | [\u09BE-\u09C0]
 | [\u09C1-\u09C4]
 | [\u09C7-\u09C8]
 | [\u09CB-\u09CC]
 | '\u09CD'
 | '\u09D7'
 | [\u09E2-\u09E3]
 | [\u09E6-\u09EF]
 | [\u0A01-\u0A02]
 | '\u0A03'
 | '\u0A3C'
 | [\u0A3E-\u0A40]
 | [\u0A41-\u0A42]
 | [\u0A47-\u0A48]
 | [\u0A4B-\u0A4D]
 | [\u0A66-\u0A6F]
 | [\u0A70-\u0A71]
 | [\u0A81-\u0A82]
 | '\u0A83'
 | '\u0ABC'
 | [\u0ABE-\u0AC0]
 | [\u0AC1-\u0AC5]
 | [\u0AC7-\u0AC8]
 | '\u0AC9'
 | [\u0ACB-\u0ACC]
 | '\u0ACD'
 | [\u0AE2-\u0AE3]
 | [\u0AE6-\u0AEF]
 | '\u0B01'
 | [\u0B02-\u0B03]
 | '\u0B3C'
 | '\u0B3E'
 | '\u0B3F'
 | '\u0B40'
 | [\u0B41-\u0B43]
 | [\u0B47-\u0B48]
 | [\u0B4B-\u0B4C]
 | '\u0B4D'
 | '\u0B56'
 | '\u0B57'
 | [\u0B66-\u0B6F]
 | '\u0B82'
 | [\u0BBE-\u0BBF]
 | '\u0BC0'
 | [\u0BC1-\u0BC2]
 | [\u0BC6-\u0BC8]
 | [\u0BCA-\u0BCC]
 | '\u0BCD'
 | '\u0BD7'
 | [\u0BE6-\u0BEF]
 | [\u0C01-\u0C03]
 | [\u0C3E-\u0C40]
 | [\u0C41-\u0C44]
 | [\u0C46-\u0C48]
 | [\u0C4A-\u0C4D]
 | [\u0C55-\u0C56]
 | [\u0C66-\u0C6F]
 | [\u0C82-\u0C83]
 | '\u0CBC'
 | '\u0CBE'
 | '\u0CBF'
 | [\u0CC0-\u0CC4]
 | '\u0CC6'
 | [\u0CC7-\u0CC8]
 | [\u0CCA-\u0CCB]
 | [\u0CCC-\u0CCD]
 | [\u0CD5-\u0CD6]
 | [\u0CE6-\u0CEF]
 | [\u0D02-\u0D03]
 | [\u0D3E-\u0D40]
 | [\u0D41-\u0D43]
 | [\u0D46-\u0D48]
 | [\u0D4A-\u0D4C]
 | '\u0D4D'
 | '\u0D57'
 | [\u0D66-\u0D6F]
 | [\u0D82-\u0D83]
 | '\u0DCA'
 | [\u0DCF-\u0DD1]
 | [\u0DD2-\u0DD4]
 | '\u0DD6'
 | [\u0DD8-\u0DDF]
 | [\u0DF2-\u0DF3]
 | '\u0E31'
 | [\u0E34-\u0E3A]
 | [\u0E47-\u0E4E]
 | [\u0E50-\u0E59]
 | '\u0EB1'
 | [\u0EB4-\u0EB9]
 | [\u0EBB-\u0EBC]
 | [\u0EC8-\u0ECD]
 | [\u0ED0-\u0ED9]
 | [\u0F18-\u0F19]
 | [\u0F20-\u0F29]
 | '\u0F35'
 | '\u0F37'
 | '\u0F39'
 | [\u0F3E-\u0F3F]
 | [\u0F71-\u0F7E]
 | '\u0F7F'
 | [\u0F80-\u0F84]
 | [\u0F86-\u0F87]
 | [\u0F90-\u0F97]
 | [\u0F99-\u0FBC]
 | '\u0FC6'
 | '\u102C'
 | [\u102D-\u1030]
 | '\u1031'
 | '\u1032'
 | [\u1036-\u1037]
 | '\u1038'
 | '\u1039'
 | [\u1040-\u1049]
 | [\u1056-\u1057]
 | [\u1058-\u1059]
 | '\u135F'
 | [\u1369-\u1371]
 | [\u1712-\u1714]
 | [\u1732-\u1734]
 | [\u1752-\u1753]
 | [\u1772-\u1773]
 | '\u17B6'
 | [\u17B7-\u17BD]
 | [\u17BE-\u17C5]
 | '\u17C6'
 | [\u17C7-\u17C8]
 | [\u17C9-\u17D3]
 | '\u17DD'
 | [\u17E0-\u17E9]
 | [\u180B-\u180D]
 | [\u1810-\u1819]
 | '\u18A9'
 | [\u1920-\u1922]
 | [\u1923-\u1926]
 | [\u1927-\u1928]
 | [\u1929-\u192B]
 | [\u1930-\u1931]
 | '\u1932'
 | [\u1933-\u1938]
 | [\u1939-\u193B]
 | [\u1946-\u194F]
 | [\u19B0-\u19C0]
 | [\u19C8-\u19C9]
 | [\u19D0-\u19D9]
 | [\u1A17-\u1A18]
 | [\u1A19-\u1A1B]
 | [\u1DC0-\u1DC3]
 | [\u203F-\u2040]
 | '\u2054'
 | [\u20D0-\u20DC]
 | '\u20E1'
 | [\u20E5-\u20EB]
 | [\u302A-\u302F]
 | [\u3099-\u309A]
 | '\uA802'
 | '\uA806'
 | '\uA80B'
 | [\uA823-\uA824]
 | [\uA825-\uA826]
 | '\uA827'
 | '\uFB1E'
 | [\uFE00-\uFE0F]
 | [\uFE20-\uFE23]
 | [\uFE33-\uFE34]
 | [\uFE4D-\uFE4F]
 | [\uFF10-\uFF19]
 | '\uFF3F'
 ;

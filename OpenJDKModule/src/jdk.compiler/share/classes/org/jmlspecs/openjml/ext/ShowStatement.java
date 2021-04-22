/*
 * This file is part of the OpenJML project. 
 * Author: David R. Cok
 */
package org.jmlspecs.openjml.ext;

import static org.jmlspecs.openjml.JmlTokenKind.ENDJMLCOMMENT;

import org.jmlspecs.openjml.IJmlClauseKind;
import org.jmlspecs.openjml.JmlExtension;
import org.jmlspecs.openjml.JmlOption;
import org.jmlspecs.openjml.JmlTokenKind;
import org.jmlspecs.openjml.JmlTree.JmlAbstractStatement;
import org.jmlspecs.openjml.JmlTree.JmlStatement;
import org.jmlspecs.openjml.JmlTree.JmlStatementShow;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.JmlAttr;
import com.sun.tools.javac.parser.JmlParser;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;

/** This class handles expression extensions that take an argument list of JCExpressions.
 * Even if there are constraints on the number of arguments, it
 * is more robust to accept all of them and then issue an error in the typechecker
 * if the number of arguments is wrong.
 * 
 * @author David Cok
 *
 */// TODO: This extension is inappropriately named at present.  However, I expect that this 
// extension will be broken into individual extensions when type checking and
// RAC and ESC translation are added.
public class ShowStatement extends JmlExtension {

    public static final String showID = "show";
    
    public static final IJmlClauseKind showClause = new JmlStatementType(showID);
    

    public static class JmlStatementType extends IJmlClauseKind.Statement {
        public JmlStatementType(String keyword) { super(keyword); }
        public boolean oldNoLabelAllowed() { return true; }
        public boolean preOrOldWithLabelAllowed() { return true; }

        public JmlAbstractStatement parse(JCModifiers mods, String keyword, IJmlClauseKind clauseType, JmlParser parser) {
            int pp = parser.pos();
            int pe = parser.endPos();
            init(parser);
            if (JmlOption.langJML.equals(JmlOption.value(context, JmlOption.LANG))) {
                utils.warning(pp,"jml.not.strict","show statement");
            }
            
            parser.nextToken();

            ListBuffer<JCExpression> expressions = new ListBuffer<>();
            if (parser.token().kind != TokenKind.SEMI && parser.token().ikind != JmlTokenKind.ENDJMLCOMMENT) {
            	do {
            		int n = log.nerrors;
            		JCExpression t = parser.parseExpression();
            		if (n != log.nerrors) {
            			parser.skipToSemi();
            			break;
            		}
            		expressions.add(t);
                } while (parser.acceptIf(TokenKind.COMMA));
            }
            JmlStatementShow st = toP(parser.maker().at(pp).JmlStatementShow(showClause,expressions.toList()));
            wrapup(st, clauseType, true, true);
            return st;
        }
        
        @Override
        public Type typecheck(JmlAttr attr, JCTree expr, Env<AttrContext> env) {
            // TODO Auto-generated method stub
            return null;
        }
}
    
    
}

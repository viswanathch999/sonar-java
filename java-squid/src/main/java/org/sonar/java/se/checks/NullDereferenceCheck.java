/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.se.checks;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.model.DefaultJavaFileScannerContext;
import org.sonar.java.se.CheckerContext;
import org.sonar.java.se.ObjectConstraint;
import org.sonar.java.se.ProgramState;
import org.sonar.java.se.SymbolicValue;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Rule(
  key = "S2259",
  name = "Null pointers should not be dereferenced",
  priority = Priority.BLOCKER)
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.LOGIC_RELIABILITY)
@SqaleConstantRemediation("10min")
public class NullDereferenceCheck extends SECheck implements JavaFileScanner {

  @Override
  public void scanFile(JavaFileScannerContext context) {
    Multimap<Tree, String> issues = ((DefaultJavaFileScannerContext) context).getSEIssues(NullDereferenceCheck.class);
    for (Map.Entry<Tree, String> issue : issues.entries()) {
      context.reportIssue(this, issue.getKey(), issue.getValue());
    }
  }

  @Override
  public ProgramState checkPreStatement(CheckerContext context, Tree syntaxNode) {
    SymbolicValue currentVal = context.getState().peekValue();
    if (currentVal == null) {
      // stack is empty, nothing to do.
      return context.getState();
    }
    Tree toCheck = syntaxNode;
    if (syntaxNode.is(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree methodInvocation = (MethodInvocationTree) syntaxNode;
      toCheck = methodInvocation.methodSelect();
    }
    if (toCheck.is(Tree.Kind.MEMBER_SELECT)) {
      return checkMemberSelect(context, (MemberSelectExpressionTree) toCheck, currentVal);
    }
    return context.getState();
  }

  private ProgramState checkMemberSelect(CheckerContext context, MemberSelectExpressionTree syntaxNode, SymbolicValue currentVal) {
    final ProgramState programState = context.getState();
    if ("class".equals(syntaxNode.identifier().name())) {
      // expression ClassName.class won't raise NPE.
      return programState;
    }

    if (context.isNull(currentVal)) {
      context.reportIssue(syntaxNode, this, "NullPointerException might be thrown as '" + SyntaxTreeNameFinder.getName(syntaxNode) + "' is nullable here");
      return null;
    }
    final SymbolicValue targetValue = programState.peekValue();
    final Object constraint = programState.getConstraint(targetValue);
    if (constraint == null) {
      // We dereferenced the target value for the member select, so we can assume it is not null when not already known
      return programState.addConstraint(targetValue, ObjectConstraint.NOT_NULL);
    }
    return programState;
  }

  @Override
  public ProgramState checkPostStatement(CheckerContext context, Tree syntaxNode) {
    if (syntaxNode.is(Tree.Kind.SWITCH_STATEMENT) && context.isNull(context.getState().peekValue())) {
      context.reportIssue(syntaxNode, this, "NullPointerException might be thrown as '" + SyntaxTreeNameFinder.getName(syntaxNode) + "' is nullable here");
      context.createSink();
      return context.getState();
    }
    List<ProgramState> programStates = setNullConstraint(context, syntaxNode);
    for (ProgramState programState : programStates) {
      context.addTransition(programState);
    }
    return context.getState();
  }

  private static List<ProgramState> setNullConstraint(CheckerContext context, Tree syntaxNode) {
    SymbolicValue val = context.getState().peekValue();
    if (syntaxNode.is(Tree.Kind.METHOD_INVOCATION) && isAnnotatedCheckForNull((MethodInvocationTree) syntaxNode)) {
      List<ProgramState> states = new ArrayList<>();
      states.addAll(val.setConstraint(context.getState(), ObjectConstraint.NULL));
      states.addAll(val.setConstraint(context.getState(), ObjectConstraint.NOT_NULL));
      return states;
    }
    return Lists.newArrayList(context.getState());
  }

  private static boolean isAnnotatedCheckForNull(MethodInvocationTree syntaxNode) {
    return syntaxNode.symbol().metadata().isAnnotatedWith("javax.annotation.CheckForNull");
  }

}

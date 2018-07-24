/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.relational;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.sql.tree.QualifiedName;

import static com.facebook.presto.spi.StandardErrorCode.FUNCTION_NOT_FOUND;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypeSignatures;
import static java.util.Objects.requireNonNull;

public class DeterminismEvaluator
{
    final FunctionManager functionManager;

    public DeterminismEvaluator(FunctionManager functionManager)
    {
        this.functionManager = requireNonNull(functionManager, "functionManager is null");
    }

    public boolean isDeterministic(RowExpression expression, Session session)
    {
        return expression.accept(new Visitor(functionManager, session), null);
    }

    private static class Visitor
            implements RowExpressionVisitor<Boolean, Void>
    {
        private final FunctionManager registry;
        private final Session session;

        public Visitor(FunctionManager registry, Session session)
        {
            this.registry = registry;
            this.session = session;
        }

        @Override
        public Boolean visitInputReference(InputReferenceExpression reference, Void context)
        {
            return true;
        }

        @Override
        public Boolean visitConstant(ConstantExpression literal, Void context)
        {
            return true;
        }

        @Override
        public Boolean visitCall(CallExpression call, Void context)
        {
            Signature signature = call.getSignature();

            try {
                FunctionHandle handle = registry.resolveFunction(session, QualifiedName.of(signature.getName()), fromTypeSignatures(signature.getArgumentTypes()));
                if (!registry.getScalarFunctionImplementation(handle).isDeterministic()) {
                    return false;
                }
            }
            catch (PrestoException e) {
                if (!e.getErrorCode().equals(FUNCTION_NOT_FOUND.toErrorCode())) {
                    throw e;
                }
            }

            return call.getArguments().stream()
                    .allMatch(expression -> expression.accept(this, context));
        }

        @Override
        public Boolean visitLambda(LambdaDefinitionExpression lambda, Void context)
        {
            return lambda.getBody().accept(this, context);
        }

        @Override
        public Boolean visitVariableReference(VariableReferenceExpression reference, Void context)
        {
            return true;
        }
    }
}

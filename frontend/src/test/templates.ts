import { dirname, join } from 'node:path';
import ts from 'typescript';

export const ANY_TEXT = '\u0000';

export type Template = { file: string; texts: string[]; readsObject: boolean };

const MOST_TEXTS = 2000;

const CASE_CHANGES: Record<string, (value: string) => string> = {
  toLowerCase: (value) => value.toLowerCase(),
  toUpperCase: (value) => value.toUpperCase(),
};

function program(root: string): ts.Program {
  const configFile = join(root, 'tsconfig.json');
  const config = ts.readConfigFile(configFile, ts.sys.readFile);
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, dirname(configFile));
  return ts.createProgram(parsed.fileNames, parsed.options);
}

function literals(checker: ts.TypeChecker, type: ts.Type): string[] | undefined {
  if (type.isUnion()) {
    const parts = type.types.map((part) => literals(checker, part));
    return parts.every((part) => part !== undefined) ? parts.flat() : undefined;
  }
  if (type.isStringLiteral()) return [type.value];
  if (type.isNumberLiteral()) return [String(type.value)];
  if (type.flags & ts.TypeFlags.BooleanLiteral) return [checker.typeToString(type)];
  if (type.flags & (ts.TypeFlags.Undefined | ts.TypeFlags.Null)) return [];
  if (type.flags & ts.TypeFlags.TypeParameter) {
    const constraint = checker.getBaseConstraintOfType(type);
    return constraint && constraint !== type ? literals(checker, constraint) : undefined;
  }
  return undefined;
}

function caseChange(expression: ts.Expression) {
  if (!ts.isCallExpression(expression) || expression.arguments.length > 0) return undefined;
  const callee = expression.expression;
  if (!ts.isPropertyAccessExpression(callee)) return undefined;
  const change = CASE_CHANGES[callee.name.text];
  return change && { of: callee.expression, change };
}

function values(checker: ts.TypeChecker, expression: ts.Expression): string[] | undefined {
  const changed = caseChange(expression);
  if (changed) return values(checker, changed.of)?.map(changed.change);
  return literals(checker, checker.getTypeAtLocation(expression));
}

function texts(checker: ts.TypeChecker, template: ts.TemplateExpression): string[] {
  let all = [template.head.text];
  for (const span of template.templateSpans) {
    const taken = [...new Set(values(checker, span.expression) ?? [ANY_TEXT])];
    all = all.flatMap((start) => taken.map((value) => start + value + span.literal.text));
    if (all.length > MOST_TEXTS) throw new Error(`a template with more than ${MOST_TEXTS} texts`);
  }
  return all;
}

function readsObject(template: ts.TemplateExpression): boolean {
  const call = template.parent;
  if (!ts.isCallExpression(call) || call.arguments[0] !== template) return false;
  const options = call.arguments[1];
  return (
    options !== undefined &&
    ts.isObjectLiteralExpression(options) &&
    options.properties.some((p) => p.name?.getText() === 'returnObjects')
  );
}

export function templatesIn(root: string, files: string[]): Template[] {
  const built = program(root);
  const checker = built.getTypeChecker();
  const found: Template[] = [];
  for (const file of files) {
    const source = built.getSourceFile(file);
    if (!source) throw new Error(`${file} is not in the TypeScript project`);
    const visit = (node: ts.Node): void => {
      if (ts.isTemplateExpression(node)) {
        found.push({ file, texts: texts(checker, node), readsObject: readsObject(node) });
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return found;
}

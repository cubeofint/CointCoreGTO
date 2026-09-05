function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');

    print('[CointCoreGTO FMLCoremod] initializeCoreMod DimensionCondition dual-compat');

    return {
        'DimensionCondition': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition'
            },
            'transformer': function (classNode) {
                var found = null;
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var m = classNode.methods.get(i);
                    if (m.name === 'testCondition' && m.desc.endsWith(')Z')) {
                        if (found !== null) {
                            throw new Error('Multiple boolean testCondition methods found in DimensionCondition');
                        }
                        found = m;
                    }
                }
                if (found === null) {
                    throw new Error('DimensionCondition.testCondition(...):boolean not found');
                }

                // GTCEu 1.8.0 / GTOCore 0.5.5:
                //   testCondition(GTRecipeDefinition, RecipeLogic)
                //   local 2 = RecipeLogic
                //
                // GTCEu 26.x / GTOCore 0.5.6:
                //   testCondition(IRecipeHandlerHolder, RecipeHandlerUnit, GTRecipeDefinition)
                //   local 1 = IRecipeHandlerHolder
                var contextLocal;
                var apiFlavor;
                if (found.desc.indexOf('Lcom/gregtechceu/gtceu/api/recipe/handler/IRecipeHandlerHolder;') >= 0) {
                    contextLocal = 1;
                    apiFlavor = '0.5.6+/GTCEu26';
                } else if (found.desc.indexOf('Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;') >= 0) {
                    contextLocal = 2;
                    apiFlavor = '0.5.5/GTCEu1.8';
                } else {
                    throw new Error('Unsupported DimensionCondition.testCondition descriptor: ' + found.desc);
                }

                var code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, contextLocal));
                code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    'Crazer/cubeofinterest/cointcoregto/coremod/DimensionConditionCoremodHook',
                    'testCondition',
                    '(Ljava/lang/Object;Ljava/lang/Object;)Z',
                    false
                ));
                code.add(new InsnNode(Opcodes.IRETURN));

                found.instructions.clear();
                if (found.tryCatchBlocks !== null) found.tryCatchBlocks.clear();
                found.instructions.add(code);
                found.maxStack = 2;
                if (found.maxLocals <= contextLocal) found.maxLocals = contextLocal + 1;

                print('[CointCoreGTO FMLCoremod] APPLIED to DimensionCondition.testCondition ' + found.desc + ' [' + apiFlavor + ']');
                return classNode;
            }
        }
    };
}

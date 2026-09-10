function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

    var SUPER = 'com/gtolib/api/machine/multiblock/ElectricMultiblockMachine';
    var CUSTOM_RECIPE_DESC = '(Lcom/gregtechceu/gtceu/api/recipe/handler/RecipeHandlerUnit;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipeDefinition;';

    return {
        'CointCoreGTO_GTO056_DisableBossSummoner': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gtocore.common.machine.multiblock.electric.adventure.BossSummonerMachine'
            },
            'transformer': function (classNode) {
                var createCustomRecipe = null;
                var afterWorking = null;

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);

                    if (method.name === 'createCustomRecipe' && method.desc === CUSTOM_RECIPE_DESC) {
                        if (createCustomRecipe !== null) {
                            throw new Error('Multiple BossSummonerMachine#createCustomRecipe methods found');
                        }
                        createCustomRecipe = method;
                    }

                    if (method.name === 'afterWorking' && method.desc === '()V') {
                        if (afterWorking !== null) {
                            throw new Error('Multiple BossSummonerMachine#afterWorking methods found');
                        }
                        afterWorking = method;
                    }
                }

                if (createCustomRecipe === null) {
                    throw new Error('Required method not found: BossSummonerMachine#createCustomRecipe');
                }

                if (afterWorking === null) {
                    throw new Error('Required method not found: BossSummonerMachine#afterWorking');
                }

                var disableRecipe = new InsnList();
                disableRecipe.add(new InsnNode(Opcodes.ACONST_NULL));
                disableRecipe.add(new InsnNode(Opcodes.ARETURN));
                createCustomRecipe.instructions.insert(disableRecipe);

                var disableSpawn = new InsnList();
                disableSpawn.add(new VarInsnNode(Opcodes.ALOAD, 0));
                disableSpawn.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    SUPER,
                    'afterWorking',
                    '()V',
                    false
                ));
                disableSpawn.add(new InsnNode(Opcodes.RETURN));
                afterWorking.instructions.insert(disableSpawn);

                print('[CointCoreGTO] GTO 0.5.6 Boss Summoner disabled');
                return classNode;
            }
        }
    };
}

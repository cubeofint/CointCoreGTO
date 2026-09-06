function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

    return {
        'DimensionCondition': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition'
            },
            'transformer': function (classNode) {
                var found = null;

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'testCondition' && method.desc.endsWith(')Z')) {
                        if (found !== null) {
                            throw new Error('Multiple boolean testCondition methods found in DimensionCondition');
                        }
                        found = method;
                    }
                }

                if (found === null) {
                    throw new Error('DimensionCondition.testCondition(...):boolean not found');
                }

                var returns = [];
                for (var insn = found.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.IRETURN) {
                        returns.push(insn);
                    }
                }

                if (returns.length === 0) {
                    throw new Error('DimensionCondition.testCondition has no IRETURN');
                }

                for (var r = 0; r < returns.length; r++) {
                    var ret = returns[r];
                    var keepOriginal = new LabelNode();
                    var patch = new InsnList();

                    patch.add(new InsnNode(Opcodes.DUP));
                    patch.add(new JumpInsnNode(Opcodes.IFNE, keepOriginal));
                    patch.add(new InsnNode(Opcodes.POP));
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    patch.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'Crazer/cubeofinterest/cointcoregto/coremod/DimensionConditionCoremodHook',
                        'isPersonalSpaceOverworld',
                        '(Ljava/lang/Object;Ljava/lang/Object;)Z',
                        false
                    ));
                    patch.add(keepOriginal);

                    found.instructions.insertBefore(ret, patch);
                }

                if (found.maxStack < 2) found.maxStack = 2;
                return classNode;
            }
        }
    };
}

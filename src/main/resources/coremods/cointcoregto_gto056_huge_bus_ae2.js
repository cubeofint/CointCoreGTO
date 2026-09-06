function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');

    var TARGET_CLASS = 'com.gtocore.common.machine.multiblock.part.HugeBusPartMachine$HugeNotifiableItemStackHandler';
    var METHOD_NAME = 'insertExternal';
    var METHOD_DESC = '(Lappeng/api/stacks/AEItemKey;ILappeng/api/config/Actionable;)I';

    return {
        'GTO056HugeBusAE2InsertExternalFix': {
            'target': {
                'type': 'CLASS',
                'name': TARGET_CLASS
            },
            'transformer': function (classNode) {
                var method = null;

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var candidate = classNode.methods.get(i);
                    if (candidate.name === METHOD_NAME && candidate.desc === METHOD_DESC) {
                        method = candidate;
                        break;
                    }
                }

                if (method === null) {
                    method = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, METHOD_DESC, null, null);
                    classNode.methods.add(method);
                } else {
                    method.access = (method.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) | Opcodes.ACC_PUBLIC;
                    method.instructions.clear();
                    if (method.tryCatchBlocks !== null) method.tryCatchBlocks.clear();
                    method.localVariables = null;
                }

                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
                method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    'appeng/api/stacks/AEItemKey',
                    'toStack',
                    '(I)Lnet/minecraft/world/item/ItemStack;',
                    false
                ));
                method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));

                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
                method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    'appeng/api/config/Actionable',
                    'isSimulate',
                    '()Z',
                    false
                ));
                method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    'com/gregtechceu/gtceu/api/machine/trait/NotifiableItemStackHandler',
                    'insertItem',
                    '(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;',
                    false
                ));
                method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));

                method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
                method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    'net/minecraft/world/item/ItemStack',
                    'm_41613_',
                    '()I',
                    false
                ));
                method.instructions.add(new InsnNode(Opcodes.ISUB));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));

                method.maxStack = 4;
                method.maxLocals = 6;
                return classNode;
            }
        }
    };
}

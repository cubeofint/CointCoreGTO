function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');
    var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');

    var REGISTRATION_OWNER = 'Crazer/cubeofinterest/cointcoregto/bosssim/gt/BossSimulationGTRegistration';
    var RENDER_OWNER = 'Crazer/cubeofinterest/cointcoregto/bosssim/gt/BossSimulationRenderHooks';
    var RENDER_DESC = '(Ljava/util/List;Lcom/gregtechceu/gtceu/api/machine/MachineDefinition;Lcom/gregtechceu/gtceu/api/machine/MetaMachine;Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/Direction;Lnet/minecraft/client/resources/model/ModelState;)V';
    var PREVIEW_OWNER = 'Crazer/cubeofinterest/cointcoregto/bosssim/gt/BossSimulationPreviewHooks';

    function patchRenderMachine(classNode, className, logText) {
        var found = null;
        for (var i = 0; i < classNode.methods.size(); i++) {
            var method = classNode.methods.get(i);
            if (method.name === 'renderMachine' && method.desc === RENDER_DESC) {
                if (found !== null) {
                    throw new Error('Multiple ' + className + '#renderMachine methods found');
                }
                found = method;
            }
        }
        if (found === null) {
            throw new Error('Required method not found: ' + className + '#renderMachine');
        }

        var keepRendering = new LabelNode();
        var patch = new InsnList();
        patch.add(new VarInsnNode(Opcodes.ALOAD, 3));
        patch.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            RENDER_OWNER,
            'shouldHideMachineRender',
            '(Lcom/gregtechceu/gtceu/api/machine/MetaMachine;)Z',
            false
        ));
        patch.add(new JumpInsnNode(Opcodes.IFEQ, keepRendering));
        patch.add(new InsnNode(Opcodes.RETURN));
        patch.add(keepRendering);
        found.instructions.insert(patch);
        print(logText);
        return classNode;
    }

    return {
        'CointCoreGTO_GTO056_BossSimulation_RegisterMachine': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gtocore.common.data.GTOMachines'
            },
            'transformer': function (classNode) {
                var found = null;
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'init' && method.desc === '()V') {
                        if (found !== null) {
                            throw new Error('Multiple GTOMachines#init()V methods found');
                        }
                        found = method;
                    }
                }
                if (found === null) {
                    throw new Error('Required method not found: com.gtocore.common.data.GTOMachines#init()V');
                }
                var returns = [];
                for (var insn = found.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.RETURN) {
                        returns.push(insn);
                    }
                }
                if (returns.length !== 1) {
                    throw new Error('Expected exactly one RETURN in GTOMachines#init()V, found ' + returns.length);
                }
                found.instructions.insertBefore(
                    returns[0],
                    new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        REGISTRATION_OWNER,
                        'registerMachineDuringGtoInit',
                        '()V',
                        false
                    )
                );
                return classNode;
            }
        },
        'CointCoreGTO_GTCEU2673_BossSimulation_HideBaseMachineParts': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.client.renderer.machine.MachineRenderer'
            },
            'transformer': function (classNode) {
                return patchRenderMachine(
                    classNode,
                    'MachineRenderer',
                    '[CointCoreGTO] Boss Simulation Chamber v5.12.27 patched GTCEu MachineRenderer'
                );
            }
        },
        'CointCoreGTO_GTCEU2673_BossSimulation_HideOverlayTieredParts': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.client.renderer.machine.OverlayTieredMachineRenderer'
            },
            'transformer': function (classNode) {
                return patchRenderMachine(
                    classNode,
                    'OverlayTieredMachineRenderer',
                    '[CointCoreGTO] Boss Simulation Chamber v5.12.27 patched GTCEu OverlayTieredMachineRenderer'
                );
            }
        },
        'CointCoreGTO_GTCEU2673_BossSimulation_InWorldPreviewRenderStates': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.client.renderer.MultiblockInWorldPreviewRenderer'
            },
            'transformer': function (classNode) {
                var found = null;
                var desc = '(Lcom/lowdragmc/lowdraglib/utils/TrackedDummyWorld;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;Lnet/minecraft/client/renderer/RenderType;Lcom/lowdragmc/lowdraglib/client/scene/WorldSceneRenderer$VertexConsumerWrapper;Ljava/util/Map;)V';
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'renderBlocks' && method.desc === desc) {
                        if (found !== null) {
                            throw new Error('Multiple MultiblockInWorldPreviewRenderer#renderBlocks methods found');
                        }
                        found = method;
                    }
                }
                if (found === null) {
                    throw new Error('Required method not found: MultiblockInWorldPreviewRenderer#renderBlocks');
                }

                var stateStore = null;
                for (var insn = found.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL &&
                        insn.owner === 'com/lowdragmc/lowdraglib/utils/BlockInfo' &&
                        insn.name === 'getBlockState' &&
                        insn.desc === '()Lnet/minecraft/world/level/block/state/BlockState;') {
                        if (stateStore !== null) {
                            throw new Error('Multiple BlockInfo#getBlockState calls found in renderBlocks');
                        }
                        var next = insn.getNext();
                        while (next !== null && next.getOpcode() < 0) {
                            next = next.getNext();
                        }
                        if (next === null || next.getOpcode() !== Opcodes.ASTORE) {
                            throw new Error('BlockState ASTORE not found after BlockInfo#getBlockState');
                        }
                        stateStore = next;
                    }
                }
                if (stateStore === null) {
                    throw new Error('BlockInfo#getBlockState call not found in renderBlocks');
                }

                var patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, stateStore.var));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 5));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PREVIEW_OWNER,
                    'resolveRenderState',
                    '(Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/Map;)Lnet/minecraft/world/level/block/state/BlockState;',
                    false
                ));
                patch.add(new VarInsnNode(Opcodes.ASTORE, stateStore.var));
                found.instructions.insert(stateStore, patch);
                print('[CointCoreGTO] Boss Simulation Chamber v5.12.27 patched GTCEu in-world preview render states');
                return classNode;
            }
        }
    };
}

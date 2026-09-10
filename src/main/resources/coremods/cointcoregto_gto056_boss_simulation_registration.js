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
                    '[CointCoreGTO] Boss Simulation Chamber v5.12.32 patched GTCEu MachineRenderer'
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
                    '[CointCoreGTO] Boss Simulation Chamber v5.12.32 patched GTCEu OverlayTieredMachineRenderer'
                );
            }
        },
        'CointCoreGTO_GTO056_BossSimulation_FullscreenPatternPreviewHatches': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gtocore.client.gui.PatternPreview'
            },
            'transformer': function (classNode) {
                var foundMethod = null;
                var methodDesc = '(Lcom/gtolib/api/machine/MultiblockDefinition;Lcom/gtolib/api/machine/MultiblockDefinition$Pattern;IZ)Lcom/gtocore/client/gui/PatternPreview$MBPattern;';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'initializePattern' && method.desc === methodDesc) {
                        if (foundMethod !== null) throw new Error('Multiple PatternPreview#initializePattern methods found');
                        foundMethod = method;
                    }
                }
                if (foundMethod === null) {
                    throw new Error('Required method not found: com.gtocore.client.gui.PatternPreview#initializePattern');
                }

                var flattenCall = null;
                for (var insn = foundMethod.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL
                            && insn.owner === 'it/unimi/dsi/fastutil/objects/Reference2ReferenceOpenHashMap'
                            && insn.name === 'forEach'
                            && insn.desc === '(Ljava/util/function/BiConsumer;)V') {
                        if (flattenCall !== null) {
                            throw new Error('Multiple PatternPreview initializePattern flatten forEach calls found');
                        }
                        flattenCall = insn;
                    }
                }
                if (flattenCall === null) {
                    throw new Error('PatternPreview initializePattern flatten forEach call not found');
                }

                var patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 7));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 8));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PREVIEW_OWNER,
                    'patchGtoFullscreenPreview',
                    '(Lnet/minecraft/core/BlockPos;Lit/unimi/dsi/fastutil/longs/Long2ReferenceOpenHashMap;)V',
                    false
                ));
                foundMethod.instructions.insert(flattenCall, patch);

                print('[CointCoreGTO] Boss Simulation Chamber v5.12.32 patched GTO PatternPreview hatch map');
                return classNode;
            }
        },
        'CointCoreGTO_GTCEU2673_BossSimulation_TerminalPreviewRedirect': {
            'target': {
                'type': 'CLASS',
                'name': 'com.gregtechceu.gtceu.common.item.TerminalBehavior'
            },
            'transformer': function (classNode) {
                var foundMethod = null;
                var methodDesc = '(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;';
                var previewDesc = '(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;Lcom/gregtechceu/gtceu/api/pattern/MultiblockShapeInfo;I)V';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name === 'onItemUseFirst' && method.desc === methodDesc) {
                        if (foundMethod !== null) throw new Error('Multiple TerminalBehavior#onItemUseFirst methods found');
                        foundMethod = method;
                    }
                }
                if (foundMethod === null) throw new Error('Required method not found: TerminalBehavior#onItemUseFirst');

                var previewCall = null;
                for (var insn = foundMethod.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.INVOKESTATIC
                            && insn.owner === 'com/gregtechceu/gtceu/client/renderer/MultiblockInWorldPreviewRenderer'
                            && insn.name === 'showPreview'
                            && insn.desc === previewDesc) {
                        if (previewCall !== null) throw new Error('Multiple terminal showPreview calls found');
                        previewCall = insn;
                    }
                }
                if (previewCall === null) throw new Error('TerminalBehavior showPreview call not found');

                previewCall.owner = PREVIEW_OWNER;
                previewCall.name = 'showPreview';
                previewCall.itf = false;
                print('[CointCoreGTO] Boss Simulation Chamber v5.12.32 redirected terminal in-world preview');
                return classNode;
            }
        }
    };
}

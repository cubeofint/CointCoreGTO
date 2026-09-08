function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

    var TARGET_CLASS = 'com.gtocore.common.data.GTOMachines';
    var HOOK_OWNER = 'Crazer/cubeofinterest/cointcoregto/bosssim/gt/BossSimulationGTRegistration';

    function findInit(classNode) {
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
            throw new Error('Required method not found: ' + TARGET_CLASS + '#init()V');
        }
        return found;
    }

    return {
        'CointCoreGTO_GTO056_BossSimulation_RegisterMachine': {
            'target': {
                'type': 'CLASS',
                'name': TARGET_CLASS
            },
            'transformer': function (classNode) {
                var method = findInit(classNode);
                var returns = [];

                for (var insn = method.instructions.getFirst(); insn !== null; insn = insn.getNext()) {
                    if (insn.getOpcode() === Opcodes.RETURN) {
                        returns.push(insn);
                    }
                }

                if (returns.length !== 1) {
                    throw new Error('Expected exactly one RETURN in ' + TARGET_CLASS
                        + '#init()V, found ' + returns.length);
                }

                method.instructions.insertBefore(
                    returns[0],
                    new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HOOK_OWNER,
                        'registerMachineDuringGtoInit',
                        '()V',
                        false
                    )
                );

                return classNode;
            }
        }
    };
}
